package ilab.iptv.spike

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Snapshot inclusion probe (`tools/snapshot/probe.sh`): plays each URL on the real device and
 * measures the two shipped criteria — **first frame <= firstFrameTimeoutMs** and **no stall
 * (buffering / frozen position) lasting >= stallMs during a holdMs window after the first frame**.
 *
 * It is a measurement fixture (same engine family as the app: Media3 ExoPlayer + HLS), not product
 * code: it reuses the app's start-up-first load control (docs/02 §7.5) and touches no production
 * contract.
 *
 * adb:
 *   am start -n ilab.iptv.player.spike/ilab.iptv.spike.HoldProbeActivity \
 *     -e in probe-input.txt \
 *     -e holdMs 30000 -e firstFrameTimeoutMs 3000 -e stallMs 2000 -e out hold
 *
 * `probe-input.txt` lives in the spike app's external files dir and holds one sample per line,
 * `<display name>\t<url>` (a bare URL is also accepted). A file beats `-e urls` because shell
 * quoting of `&` and the ~4 KB shell argument limit both bite at snapshot scale.
 *
 * Output: `/sdcard/Android/data/ilab.iptv.player.spike/files/<out>.jsonl` (one line per URL, flushed
 * as the run goes so a killed run still leaves usable data) + `<out>.json` summary at the end.
 */
@OptIn(UnstableApi::class)
class HoldProbeActivity : Activity(), SurfaceHolder.Callback, Player.Listener {

    private lateinit var surfaceView: SurfaceView
    private var surfaceReady = false
    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    private var urls: List<String> = emptyList()
    private var names: List<String> = emptyList()
    private var holdMs = 30_000L
    private var firstFrameTimeoutMs = 3_000L
    private var stallMs = 2_000L
    private var gapMs = 400L
    private var tickMs = 200L
    private var out = "hold"

    private var qi = 0
    @Volatile
    private var sampleOpen = false
    @Volatile
    private var finished = false
    private var prepareStartUptime = 0L
    private var firstFrameUptime = 0L
    private var lastAdvanceUptime = 0L
    private var lastPos = -1L
    private var lastBuf = -1L
    private var progressStart = -1L
    private var progressEnd = -1L
    private var stallStartUptime = 0L
    private var stallTotalMs = 0L
    private var stallMaxMs = 0L
    private var stallCount = 0
    private var verdict = ""
    private var errorAtFirstFrame = false

    private val results = JSONArray()
    private var passed = 0

    /**
     * The TV can still drop the surface mid-run (screen standby). When that happens the handler
     * stops firing on time and the decoder is no longer trustworthy, so a background watchdog is
     * the only thing that can move the run along (`surfaceCreated` rebuilds the player as well).
     */
    private val watchdog = Thread {
        while (!finished) {
            try {
                Thread.sleep(5_000)
            } catch (_: InterruptedException) {
                return@Thread
            }
            val limit = firstFrameTimeoutMs + holdMs + 60_000
            if (sampleOpen && SystemClock.uptimeMillis() - prepareStartUptime > limit) {
                Log.w(SpikeIo.TAG, "HOLD_WATCHDOG idx=$qi stuck for more than $limit ms")
                handler.post { settle(qi - 1, "watchdog_stuck", player?.playerError) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val inline = intent.getStringExtra("urls")
        val inFile = intent.getStringExtra("in")
        if (!inline.isNullOrBlank()) {
            urls = inline.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            names = (intent.getStringExtra("names") ?: "").split(",")
        } else if (!inFile.isNullOrBlank()) {
            val dir = getExternalFilesDir(null) ?: filesDir
            val pairs = File(dir, inFile).readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map {
                    val tab = it.indexOf('\t')
                    if (tab > 0) it.substring(0, tab) to it.substring(tab + 1).trim() else it to ""
                }
            urls = pairs.map { it.second }
            names = pairs.map { it.first }
        }
        holdMs = (intent.getStringExtra("holdMs") ?: "30000").toLong()
        firstFrameTimeoutMs = (intent.getStringExtra("firstFrameTimeoutMs") ?: "3000").toLong()
        stallMs = (intent.getStringExtra("stallMs") ?: "2000").toLong()
        gapMs = (intent.getStringExtra("gapMs") ?: "400").toLong()
        out = intent.getStringExtra("out") ?: "hold"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(this)
        setContentView(surfaceView)
        watchdog.isDaemon = true
        watchdog.start()
        Log.i(SpikeIo.TAG, "HOLD_RUN_START out=$out samples=${urls.size} holdMs=$holdMs " +
            "firstFrameTimeoutMs=$firstFrameTimeoutMs stallMs=$stallMs")
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val wasReady = surfaceReady
        surfaceReady = true
        if (player == null) {
            player = buildPlayer()
            player?.setVideoSurfaceView(surfaceView)
            next()
            return
        }
        if (!wasReady) {
            // Screen standby tears the surface down; the renderer that comes back is not the one
            // that produced the first frames, so rebuild the player and re-run the open sample.
            val retryIdx = if (sampleOpen) qi - 1 else qi
            Log.w(SpikeIo.TAG, "HOLD_SURFACE_REBUILD retryIdx=${retryIdx + 1}")
            handler.removeCallbacksAndMessages(null)
            sampleOpen = false
            player?.release()
            player = buildPlayer()
            player?.setVideoSurfaceView(surfaceView)
            handler.post {
                if (retryIdx in urls.indices) startSample(retryIdx) else next()
            }
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
    }

    private fun buildPlayer(): ExoPlayer {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1_500, 5_000, 800, 1_500)
            .build()
        val built = ExoPlayer.Builder(this, DefaultRenderersFactory(this))
            .setTrackSelector(DefaultTrackSelector(this))
            .setLoadControl(loadControl)
            .build()
        built.addListener(this)
        built.setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ false)
        return built
    }

    private fun next() {
        if (qi >= urls.size) {
            finishRun()
            return
        }
        startSample(qi)
    }

    private fun startSample(index: Int) {
        val active = player ?: return
        qi = index + 1
        active.stop()
        active.clearMediaItems()
        sampleOpen = true
        verdict = ""
        firstFrameUptime = 0L
        progressStart = -1L
        progressEnd = -1L
        stallStartUptime = 0L
        stallTotalMs = 0L
        stallMaxMs = 0L
        stallCount = 0
        lastPos = -1L
        lastBuf = -1L
        errorAtFirstFrame = false
        prepareStartUptime = SystemClock.uptimeMillis()
        lastAdvanceUptime = prepareStartUptime
        Log.i(SpikeIo.TAG, "HOLD_SAMPLE_START idx=${index + 1} url=${redact(urls[index])}")
        active.setMediaItem(MediaItem.fromUri(urls[index]))
        active.prepare()
        active.play()
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ onFirstFrameTimeout(index) }, firstFrameTimeoutMs)
        handler.post(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!sampleOpen) return
            val active = player ?: return
            val now = SystemClock.uptimeMillis()
            val idx = qi - 1
            val pos = active.currentPosition
            val buf = active.bufferedPosition
            if (pos != lastPos || buf != lastBuf) {
                lastPos = pos
                lastBuf = buf
                lastAdvanceUptime = now
                if (stallStartUptime > 0L) {
                    val episode = now - stallStartUptime
                    stallTotalMs += episode
                    if (episode > stallMaxMs) stallMaxMs = episode
                    stallStartUptime = 0L
                }
            }
            if (firstFrameUptime > 0L) {
                if (progressStart < 0L) progressStart = maxOf(pos, buf)
                progressEnd = maxOf(pos, buf)
                val frozenMs = now - lastAdvanceUptime
                if (frozenMs >= stallMs && stallStartUptime == 0L) {
                    stallStartUptime = lastAdvanceUptime
                    stallCount++
                }
                if (now - firstFrameUptime >= holdMs) {
                    settle(idx, if (stallCount == 0) "pass" else "stall", null)
                    return
                }
            }
            handler.postDelayed(this, tickMs)
        }
    }

    private fun onFirstFrameTimeout(index: Int) {
        if (!sampleOpen || firstFrameUptime > 0L) return
        settle(index, "no_first_frame", player?.playerError)
    }

    override fun onRenderedFirstFrame() {
        val idx = qi - 1
        if (firstFrameUptime == 0L) {
            firstFrameUptime = SystemClock.uptimeMillis()
            lastAdvanceUptime = firstFrameUptime
            Log.i(SpikeIo.TAG, "HOLD_FIRST_FRAME idx=${idx + 1} " +
                "costMs=${firstFrameUptime - prepareStartUptime}")
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        if (firstFrameUptime == 0L) errorAtFirstFrame = true
        settle(qi - 1, if (firstFrameUptime == 0L) "error_before_first_frame" else "error_after_first_frame", error)
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED && sampleOpen && firstFrameUptime > 0L) {
            settle(qi - 1, "ended", null)
        }
    }

    private fun settle(index: Int, outcome: String, error: PlaybackException?) {
        if (!sampleOpen) return
        sampleOpen = false
        handler.removeCallbacksAndMessages(null)
        val active = player
        val now = SystemClock.uptimeMillis()
        if (firstFrameUptime > 0L) {
            val frozen = now - lastAdvanceUptime
            if (stallStartUptime > 0L || frozen >= stallMs) {
                val episode = if (stallStartUptime > 0L) now - stallStartUptime else frozen
                stallTotalMs += episode
                if (episode > stallMaxMs) stallMaxMs = episode
                if (stallStartUptime == 0L) stallCount++
                stallStartUptime = 0L
            }
        }
        val firstFrameMs = if (firstFrameUptime > 0L) firstFrameUptime - prepareStartUptime else -1L
        val heldMs = if (firstFrameUptime > 0L) now - firstFrameUptime else 0L
        val advancedMs = if (progressStart >= 0L && progressEnd >= 0L) progressEnd - progressStart else -1L
        val isPass = outcome == "pass" &&
            firstFrameMs in 1..firstFrameTimeoutMs &&
            stallCount == 0
        if (isPass) passed++
        val entry = JSONObject()
            .put("idx", index + 1)
            .put("name", names.getOrNull(index)?.takeIf { it.isNotBlank() } ?: "url-${index + 1}")
            .put("url", urls.getOrNull(index) ?: "")
            .put("outcome", outcome)
            .put("pass", isPass)
            .put("firstFrameMs", firstFrameMs)
            .put("heldMs", heldMs)
            .put("stallCount", stallCount)
            .put("stallTotalMs", stallTotalMs)
            .put("stallMaxMs", stallMaxMs)
            .put("advancedMs", advancedMs)
            .put("positionMs", active?.currentPosition ?: -1L)
            .put("bufferedPositionMs", active?.bufferedPosition ?: -1L)
            .put("playbackState", active?.playbackState ?: -1)
            .put("isPlaying", active?.isPlaying ?: false)
            .put("videoFormat", formatJson(active?.videoFormat))
            .put("audioFormat", formatJson(active?.audioFormat))
            .put("error", errorJson(error ?: active?.playerError))
        results.put(entry)
        append(entry)
        Log.i(SpikeIo.TAG, "HOLD_RESULT idx=${index + 1} outcome=$outcome pass=$isPass " +
            "firstFrameMs=$firstFrameMs heldMs=$heldMs stalls=$stallCount stallTotalMs=$stallTotalMs " +
            "stallMaxMs=$stallMaxMs advancedMs=$advancedMs " +
            "v=${active?.videoFormat?.let { "${it.width}x${it.height}/${it.codecs}" } ?: "-"} " +
            "err=${(error ?: active?.playerError)?.errorCodeName ?: "-"}")
        handler.postDelayed({ next() }, gapMs)
    }

    private fun append(entry: JSONObject) {
        runCatching {
            val dir = getExternalFilesDir(null) ?: filesDir
            File(dir, "$out.jsonl").appendText(entry.toString() + "\n")
        }
    }

    private fun finishRun() {
        finished = true
        val summary = JSONObject()
            .put("device", android.os.Build.MODEL)
            .put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("holdMs", holdMs)
            .put("firstFrameTimeoutMs", firstFrameTimeoutMs)
            .put("stallMs", stallMs)
            .put("samples", results.length())
            .put("passed", passed)
            .put("entries", results)
        runCatching {
            val dir = getExternalFilesDir(null) ?: filesDir
            File(dir, "$out.json").writeText(summary.toString(1))
        }
        Log.i(SpikeIo.TAG, "HOLD_RUN_DONE samples=${results.length()} passed=$passed")
        handler.postDelayed({
            player?.release()
            player = null
            finish()
        }, 300L)
    }

    private fun redact(url: String): String {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: "?"
        return "scheme://$host/<redacted>"
    }

    private fun formatJson(f: Format?): Any = if (f == null) JSONObject.NULL else JSONObject()
        .put("mime", f.sampleMimeType)
        .put("codecs", f.codecs)
        .put("width", f.width)
        .put("height", f.height)
        .put("sampleRate", f.sampleRate)
        .put("channelCount", f.channelCount)

    private fun errorJson(e: PlaybackException?): Any = if (e == null) JSONObject.NULL else JSONObject()
        .put("errorCode", e.errorCode)
        .put("errorCodeName", e.errorCodeName)
        .put("message", e.message?.take(200))
        .put("causeClass", e.cause?.javaClass?.simpleName)

    override fun onDestroy() {
        super.onDestroy()
        finished = true
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
    }
}
