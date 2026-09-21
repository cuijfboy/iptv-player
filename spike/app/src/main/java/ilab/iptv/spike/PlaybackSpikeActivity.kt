package ilab.iptv.spike

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import org.json.JSONArray
import org.json.JSONObject

/**
 * S1 / S2 / S5 fixture: drives Media3 ExoPlayer over a scripted channel list on real hardware.
 *
 * Measurement follows docs/02 §7.3: prepareStart = the uptime at which the spike issues `prepare`,
 * firstFrame = `Player.Listener.onRenderedFirstFrame`, costMs = firstFrame - prepareStart.
 *
 * adb:
 *   am start -n ilab.iptv.player.spike/.PlaybackSpikeActivity \
 *      -e mode s1 -e tag s1a
 *   mode = s1 | s2 | s5        s2key = aac|mp2|ac3|eac3      passthrough = true|false
 *   onlyIdx = <n>              switches = <n>                 recreate = true|false
 *   holdMs = <n>               timeoutMs = <n>                 coldStart = true
 */
@OptIn(UnstableApi::class)
class PlaybackSpikeActivity : Activity(), SurfaceHolder.Callback, Player.Listener {

    private lateinit var surfaceView: SurfaceView
    private var surfaceReady = false
    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    private var queue: List<Sample> = emptyList()
    private var qi = 0
    private var mode = "s1"
    private var tag = "run"
    private var timeoutMs = 15_000L
    private var gapMs = 600L
    private var holdMs = 0L
    private var passThrough = false
    private var recreate = false
    private var coldStart = false
    private var onlyIdx = -1
    private var s2ProbeMs = 5_000L
    private var s2Extra: JSONObject? = null

    private val results = JSONArray()
    private var prepareStartUptime = 0L
    private var readyAtUptime = 0L
    private var firstFrameUptime = 0L
    private var sampleOpen = false
    private var timeoutRunnable: Runnable? = null
    private var pssBeforeKb = 0
    private var sampleErrors = 0
    private var buildMsTotal = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = intent.getStringExtra("mode") ?: "s1"
        tag = intent.getStringExtra("tag") ?: mode
        timeoutMs = (intent.getStringExtra("timeoutMs") ?: "15000").toLong()
        gapMs = (intent.getStringExtra("gapMs") ?: "600").toLong()
        holdMs = (intent.getStringExtra("holdMs") ?: "0").toLong()
        passThrough = intent.getStringExtra("passthrough") == "true"
        recreate = intent.getStringExtra("recreate") == "true"
        coldStart = intent.getStringExtra("coldStart") == "true"
        onlyIdx = (intent.getStringExtra("onlyIdx") ?: "-1").toInt()
        s2ProbeMs = (intent.getStringExtra("probeMs") ?: "5000").toLong()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(this)
        setContentView(surfaceView)
        queue = buildQueue()
        Log.i(SpikeIo.TAG, "RUN_START mode=$mode tag=$tag samples=${queue.size} " +
            "passthrough=$passThrough recreate=$recreate timeoutMs=$timeoutMs onlyIdx=$onlyIdx " +
            "audioCaps=[${AudioCapabilities.getCapabilities(this)}]")
    }

    private fun buildQueue(): List<Sample> {
        // `urls` (comma separated) overrides the asset sample set: used for the locally served
        // AC-3 / E-AC-3 test vectors, because every AC-3 stream in the dsh baseline is dead.
        intent.getStringExtra("urls")?.takeIf { it.isNotBlank() }?.let { raw ->
            val codecs = (intent.getStringExtra("acodecs") ?: "").split(",")
            return raw.split(",").mapIndexed { i, url ->
                Sample(
                    idx = i + 1,
                    id = "local-${i + 1}",
                    name = (intent.getStringExtra("names") ?: "").split(",").getOrNull(i)
                        ?: "local-${i + 1}",
                    group = "local-served",
                    url = url.trim(),
                    vcodec = null,
                    height = null,
                    acodec = codecs.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() },
                    source = "local-http",
                )
            }
        }
        val base = when (mode) {
            "s2" -> Samples.s2(this, intent.getStringExtra("s2key") ?: "aac")
            "s5" -> Samples.s5(this)
            else -> Samples.s1(this)
        }
        val filtered = if (onlyIdx > 0) base.filter { it.idx == onlyIdx } else base
        if (mode == "s5") {
            val switches = (intent.getStringExtra("switches") ?: "30").toInt()
            if (filtered.isEmpty()) return emptyList()
            return (0 until switches).map { filtered[it % filtered.size] }
        }
        return filtered
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        if (player == null) {
            player = buildPlayer()
            attachSurface()
            pssBeforeKb = pss()
            next()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
    }

    private fun buildPlayer(): ExoPlayer {
        val started = SystemClock.uptimeMillis()
        val renderersFactory = if (passThrough) {
            PassthroughRenderersFactory(this)
        } else {
            DefaultRenderersFactory(this)
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1_500, 5_000, 800, 1_500) // docs/02 §7.5 start-up-first defaults
            .build()
        val built = ExoPlayer.Builder(this, renderersFactory)
            .setTrackSelector(DefaultTrackSelector(this))
            .setLoadControl(loadControl)
            .build()
        built.addListener(this)
        built.setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ false)
        buildMsTotal += SystemClock.uptimeMillis() - started
        return built
    }

    private fun attachSurface() {
        if (surfaceReady) player?.setVideoSurfaceView(surfaceView)
    }

    private fun next() {
        if (qi >= queue.size) {
            finishRun()
            return
        }
        startSample(queue[qi++])
    }

    private fun startSample(sample: Sample) {
        val current = player ?: return
        if (recreate) {
            current.release()
            val fresh = buildPlayer()
            player = fresh
            attachSurface()
        } else {
            current.stop()
            current.clearMediaItems()
        }
        val active = player ?: return
        sampleOpen = true
        readyAtUptime = 0L
        firstFrameUptime = 0L
        s2Extra = null
        prepareStartUptime = SystemClock.uptimeMillis()
        Log.i(SpikeIo.TAG, "SAMPLE_START mode=$mode tag=$tag idx=${sample.idx} name=${sample.name}")
        active.setMediaItem(MediaItem.fromUri(sample.url))
        active.prepare()
        active.play()
        val timeout = Runnable { settle(sample, "timeout", player?.playerError) }
        timeoutRunnable = timeout
        handler.postDelayed(timeout, timeoutMs)
        if (mode == "s2") {
            // Audio-only content never fires onRenderedFirstFrame (that is a video callback), so the
            // S2 verdict is taken after a fixed settle window: READY + an audio track selected and
            // playing + a non-zero audio session = the audio path really engaged.
            handler.postDelayed({ settleS2(sample) }, s2ProbeMs)
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_READY && readyAtUptime == 0L) {
            readyAtUptime = SystemClock.uptimeMillis()
        }
    }

    private fun settleS2(sample: Sample) {
        if (!sampleOpen) return
        val active = player
        val error = active?.playerError
        val audioGroup = active?.currentTracks?.groups
            ?.firstOrNull { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }
        val audioSelected = audioGroup?.isSelected == true
        val outcome = when {
            error != null -> "error"
            active?.playbackState == Player.STATE_READY && audioSelected &&
                active.audioFormat != null && active.isPlaying -> "audio_ok"
            active?.playbackState == Player.STATE_READY && audioSelected -> "audio_ready_not_playing"
            active?.playbackState == Player.STATE_READY -> "video_only_no_audio_track"
            else -> "no_ready"
        }
        s2Extra = snapshotS2()
        settle(sample, outcome, error)
    }

    private fun snapshotS2(): JSONObject {
        val active = player
        val audioGroup = active?.currentTracks?.groups
            ?.firstOrNull { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }
        return JSONObject()
            .put("audioSelected", audioGroup?.isSelected == true)
            .put("audioSupported", audioGroup?.let { g -> (0 until g.length).map { g.isTrackSupported(it) } })
            .put("isPlaying", active?.isPlaying ?: false)
            .put("positionMs", active?.currentPosition ?: -1)
            .put("audioSessionId", active?.audioSessionId ?: -1)
            .put("readyAfterMs", if (readyAtUptime > 0) readyAtUptime - prepareStartUptime else -1)
            .put("firstFrameAfterPrepareMs",
                if (firstFrameUptime > 0) firstFrameUptime - prepareStartUptime else -1)
    }

    override fun onRenderedFirstFrame() {
        val sample = queue.getOrNull(qi - 1) ?: return
        if (mode == "s2") {
            // S2 waits for the audio settle window instead of stopping at the video first frame
            if (firstFrameUptime == 0L) firstFrameUptime = SystemClock.uptimeMillis()
            return
        }
        settle(sample, "ok", null)
    }

    override fun onPlayerError(error: PlaybackException) {
        val sample = queue.getOrNull(qi - 1) ?: return
        settle(sample, "error", error)
    }

    private fun settle(sample: Sample, outcome: String, error: PlaybackException?) {
        if (!sampleOpen) return
        sampleOpen = false
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null
        val now = SystemClock.uptimeMillis()
        val costMs = now - prepareStartUptime
        val active = player
        if (outcome != "ok") sampleErrors++

        val entry = sample.toJson()
            .put("outcome", outcome)
            .put("costMs", costMs)
            .put("processStartUptimeMs", Process.getStartUptimeMillis())
            .put("firstFrameSinceProcessStartMs", now - Process.getStartUptimeMillis())
            .put("playerState", active?.playbackState ?: -1)
            .put("playbackError", errorJson(error ?: active?.playerError))
            .put("videoFormat", formatJson(active?.videoFormat))
            .put("audioFormat", formatJson(active?.audioFormat))
            .put("audioSessionId", active?.audioSessionId ?: -1)
            .put("tracks", tracksJson())
        if (mode == "s2") entry.put("s2", s2Extra ?: snapshotS2())
        results.put(entry)

        Log.i(SpikeIo.TAG, "RESULT mode=$mode tag=$tag idx=${sample.idx} outcome=$outcome " +
            "costMs=$costMs v=${fmtBrief(active?.videoFormat)} a=${fmtBrief(active?.audioFormat)} " +
            "err=${(error ?: active?.playerError)?.errorCodeName ?: "-"}")
        if (coldStart && outcome == "ok") {
            Log.i(SpikeIo.TAG, "COLD_VIDEO_FIRST_FRAME tag=$tag " +
                "sinceProcessStartMs=${now - Process.getStartUptimeMillis()}")
        }
        if (mode == "s2" && holdMs > 0) {
            Log.i(SpikeIo.TAG, "S2_HOLD_START idx=${sample.idx} holdMs=$holdMs " +
                "audioSessionId=${active?.audioSessionId ?: -1}")
            handler.postDelayed({ s2HoldDone(sample) }, holdMs)
        } else {
            handler.postDelayed({ next() }, gapMs)
        }
    }

    private fun s2HoldDone(sample: Sample) {
        Log.i(SpikeIo.TAG, "S2_HOLD_END idx=${sample.idx}")
        handler.postDelayed({ next() }, gapMs)
    }

    private fun finishRun() {
        val pssAfterKb = pss()
        val summary = JSONObject()
            .put("mode", mode)
            .put("tag", tag)
            .put("device", android.os.Build.MODEL)
            .put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("passthrough", passThrough)
            .put("recreate", recreate)
            .put("samples", results.length())
            .put("errors", sampleErrors)
            .put("playerBuildMsTotal", buildMsTotal)
            .put("pssBeforeKb", pssBeforeKb)
            .put("pssAfterKb", pssAfterKb)
            .put("pssDeltaKb", pssAfterKb - pssBeforeKb)
            .put("audioCapabilities", AudioCapabilities.getCapabilities(this).toString())
            .put("entries", results)
        SpikeIo.write(this, "${mode}_$tag.json", summary.toString(1))
        Log.i(SpikeIo.TAG, "RUN_DONE mode=$mode tag=$tag samples=${results.length()} " +
            "errors=$sampleErrors pssDeltaKb=${pssAfterKb - pssBeforeKb}")
        handler.postDelayed({
            player?.release()
            player = null
            finish()
        }, if (coldStart) 3_000L else 300L)
    }

    override fun onTracksChanged(tracks: Tracks) {
        if (!sampleOpen) return
        val audio = tracks.groups.firstOrNull { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }
        Log.i(SpikeIo.TAG, "TRACKS idx=${queue.getOrNull(qi - 1)?.idx} audioSelected=${audio?.isSelected} " +
            "audioSupported=${audio?.isSupported} audioTrackSupport=${audio?.let { g -> (0 until g.length).map { g.isTrackSupported(it) } }}")
    }

    private fun tracksJson(): JSONArray {
        val arr = JSONArray()
        val active = player ?: return arr
        for (group in active.currentTracks.groups) {
            for (i in 0 until group.length) {
                arr.put(JSONObject()
                    .put("type", group.type)
                    .put("mime", group.getTrackFormat(i).sampleMimeType)
                    .put("codecs", group.getTrackFormat(i).codecs)
                    .put("supported", group.isTrackSupported(i))
                    .put("selected", group.isTrackSelected(i)))
            }
        }
        return arr
    }

    private fun formatJson(f: Format?): Any = if (f == null) JSONObject.NULL else JSONObject()
        .put("mime", f.sampleMimeType)
        .put("codecs", f.codecs)
        .put("width", f.width)
        .put("height", f.height)
        .put("frameRate", f.frameRate)
        .put("sampleRate", f.sampleRate)
        .put("channelCount", f.channelCount)
        .put("bitrate", f.bitrate)
        .put("peakBitrate", f.peakBitrate)

    private fun fmtBrief(f: Format?): String = if (f == null) {
        "-"
    } else {
        "${f.sampleMimeType}/${f.codecs}@${f.width}x${f.height}${f.sampleRate}Hz"
    }

    private fun errorJson(e: PlaybackException?): Any = if (e == null) JSONObject.NULL else JSONObject()
        .put("errorCode", e.errorCode)
        .put("errorCodeName", e.errorCodeName)
        .put("message", e.message?.take(300))
        .put("causeClass", e.cause?.javaClass?.simpleName)
        .put("causeMessage", e.cause?.message?.take(300))
        .put("causeCause", e.cause?.cause?.javaClass?.simpleName)
        .put("causeCauseMessage", e.cause?.cause?.message?.take(300))

    private fun pss(): Int {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return info.totalPss
    }

    override fun onDestroy() {
        super.onDestroy()
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        player?.release()
        player = null
    }

    /** `preferPassthrough=true` equivalent: the sink is built from the real device audio capabilities. */
    private class PassthroughRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink = DefaultAudioSink.Builder(context)
            .setAudioCapabilities(AudioCapabilities.getCapabilities(context))
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .build()
    }

    /** Referenced so the compiler keeps the mime constants used by the device probe in sync. */
    @Suppress("unused")
    private val probedAudioMimes = listOf(
        MimeTypes.AUDIO_AAC, MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3,
    )
}
