package ilab.iptv.player.core.player

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import ilab.iptv.player.core.common.AppError
import ilab.iptv.player.core.common.AppResult
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.model.AspectRatioMode
import ilab.iptv.player.core.model.AudioTrackInfo
import ilab.iptv.player.core.model.EndReason
import ilab.iptv.player.core.model.EngineCapability
import ilab.iptv.player.core.model.EngineState
import ilab.iptv.player.core.model.PlaybackEvent
import ilab.iptv.player.core.model.PlaybackRequest
import ilab.iptv.player.core.model.PreparedMedia
import ilab.iptv.player.core.model.Stream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Media3 ExoPlayer implementation of [PlayerEngine] (docs/02 §4.4/§7, P1-3).
 *
 * CONTRACTS HONOURED
 * - **One instance, reused** (§7.2 P1/P5, S5): the ExoPlayer is built once and reused with
 *   `stop() + clearMediaItems() + prepare()`. S5 measured that the cost of rebuilding is memory
 *   (~1.25 MB/instance), not speed (~4 ms), which is why reuse is the default. `release()` is
 *   terminal — a released engine must be re-created (P5).
 * - **Single thread** (§4.5 C2): every public member runs on [dispatcher] (a single-parallelism,
 *   Looper-backed dispatcher that also serves as the ExoPlayer application looper), and [prepare]
 *   is additionally mutex-guarded. A new `prepare` cancels the old one and waits for its cleanup
 *   before issuing the new request (§7.3 互斥).
 * - **Error model** (§4.6): failures come back as [AppResult.Err] with an [AppError] from
 *   [PlaybackErrorMapper]; `CancellationException` is rethrown, never wrapped (§4.5 C5).
 * - **First frame** (§7.3): `costMs = firstFrameUptime − prepareStartUptime`, emitted as
 *   [PlaybackEvent.FirstFrame] and never logged here — the controller owns the log (see
 *   [PlaybackEventLogger]). Audio-only streams never fire `onRenderedFirstFrame`, so for a stream
 *   with no video track the same measurement is taken once the audio path is actually running
 *   (READY + playing + position advancing) — the S2 lesson, applied rather than rediscovered.
 * - **Audio** (§7.6): `preferPassthrough` picks between "hand the compressed bitstream to the HAL"
 *   and "decode to PCM"; the observed path is reported in [PlaybackSnapshot.audioPath] so
 *   `PLAY_FIRST_FRAME` can carry it. Device probing is S2's, unchanged.
 *
 * NOT HERE (boundaries): no failover decision (§6.2 is `FailoverPolicy`, P1-6), no watchdog (P1-6),
 * no Android `View`/info bar (P1-4), no MediaSession/foreground service/audio focus (P1-7).
 */
@OptIn(UnstableApi::class)
class Media3Engine(
    context: Context,
    private val dispatcher: CoroutineDispatcher,
    private val tuning: EngineTuning = EngineTuning.LIVE_DEFAULT,
    private val errorMapper: PlaybackErrorMapper = PlaybackErrorMapper(),
    private val uptimeMs: () -> Long = { SystemClock.uptimeMillis() },
) : PlayerEngine {

    private val appContext: Context = context.applicationContext

    override val id: String = Media3EngineFactory.ID
    override val capabilities: Set<EngineCapability> = Media3Capabilities.of(appContext)

    private val stateMachine = EngineStateMachine()
    private val _state = MutableStateFlow(EngineState.IDLE)
    override val state: StateFlow<EngineState> = _state

    private val _events = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<PlaybackEvent> = _events.asSharedFlow()

    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val prepareMutex = Mutex()
    private var inFlightPrepare: Job? = null

    private var player: ExoPlayer? = null
    private var surface: Surface? = null
    private var appliedPassthrough: Boolean? = null
    private var readyWaiter: CompletableDeferred<AppResult<PreparedMedia>>? = null
    private var prepareStartUptimeMs = 0L
    private var activeStreamId = 0L
    private var firstFrameEmitted = false
    private var errorReported = false
    private var released = false

    @Volatile
    private var cachedTracks: List<AudioTrackInfo> = emptyList()

    @Volatile
    private var cachedSelectedTrackId: String? = null

    @Volatile
    private var snapshotCache: PlaybackSnapshot = PlaybackSnapshot.EMPTY

    @Volatile
    private var aspectRatio: AspectRatioMode = AspectRatioMode.FIT

    // ---------------------------------------------------------------- surface / display

    override fun attach(surface: Surface?) {
        this.surface = surface
        scope.launch { ensurePlayer().setVideoSurface(surface) }
    }

    override fun setAspectRatio(mode: AspectRatioMode) {
        aspectRatio = mode
    }

    /** The frozen four-mode parameter, as set by the controller. */
    fun aspectRatio(): AspectRatioMode = aspectRatio

    // ---------------------------------------------------------------- playback commands

    override fun play() {
        scope.launch { player?.play() }
    }

    override fun pause() {
        scope.launch { player?.pause() }
    }

    override fun stop() {
        scope.launch {
            player?.stop()
            player?.clearMediaItems()
            publish(stateMachine.onPlayerPhase(PlayerPhase.IDLE))
            cachedTracks = emptyList()
            cachedSelectedTrackId = null
        }
    }

    override fun release() {
        if (released) return
        released = true
        scope.launch {
            inFlightPrepare?.cancelAndJoin()
            inFlightPrepare = null
            readyWaiter?.cancel()
            readyWaiter = null
            player?.release()
            player = null
            appliedPassthrough = null
            cachedTracks = emptyList()
            cachedSelectedTrackId = null
            snapshotCache = PlaybackSnapshot.EMPTY
            publish(stateMachine.onReleased())
        }
    }

    // ---------------------------------------------------------------- tracks

    override fun audioTracks(): List<AudioTrackInfo> = cachedTracks

    override fun selectAudioTrack(id: String?): Boolean {
        val known = id == null || cachedTracks.any { it.id == id }
        scope.launch {
            val exo = player ?: return@launch
            val builder = exo.trackSelectionParameters.buildUpon()
            val parsed = id?.let { parseAudioTrackId(it) }
            val group = parsed?.let { (groupIndex, _) ->
                exo.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.getOrNull(groupIndex)
            }
            if (parsed != null && group != null) {
                builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, parsed.second))
            } else {
                builder.clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            }
            exo.trackSelectionParameters = builder.build()
        }
        return known
    }

    // ---------------------------------------------------------------- prepare

    override suspend fun prepare(request: PlaybackRequest): AppResult<PreparedMedia> =
        withContext(dispatcher) {
            val self = coroutineContext[Job]
            val previous = prepareMutex.withLock {
                inFlightPrepare.also { inFlightPrepare = self }
            }
            if (previous != null && previous !== self) {
                // §7.3 互斥: cancel the old request and wait for its cleanup before starting the new one.
                previous.cancelAndJoin()
            }
            try {
                prepareInternal(request)
            } finally {
                prepareMutex.withLock { if (inFlightPrepare === self) inFlightPrepare = null }
            }
        }

    private suspend fun prepareInternal(request: PlaybackRequest): AppResult<PreparedMedia> {
        if (released) {
            return AppResult.Err(
                AppError.unknown(EventCodes.PLAY_PREPARE_FAIL, IllegalStateException("engine already released")),
            )
        }
        val t = tuning.normalized()
        applyPassthrough(request.preferPassthrough)
        val exo = ensurePlayer()

        exo.stop()
        exo.clearMediaItems()
        exo.setMediaSource(mediaSource(request.stream))

        val waiter = CompletableDeferred<AppResult<PreparedMedia>>()
        readyWaiter = waiter
        firstFrameEmitted = false
        errorReported = false
        activeStreamId = request.stream.id
        prepareStartUptimeMs = uptimeMs()
        publish(stateMachine.onPreparing())

        exo.prepare()
        exo.play()

        val timeoutMs = if (request.timeoutMs > 0) request.timeoutMs else t.prepareTimeoutMs
        val result = withTimeoutOrNull(timeoutMs) { waiter.await() }
            ?: AppResult.Err(
                AppError.timeout(EventCodes.PLAY_PREPARE_FAIL).copy(detail = "prepare timed out after ${timeoutMs}ms"),
            )
        readyWaiter = null
        if (result is AppResult.Err && !errorReported) {
            // The timeout path never reached the player listener, so the controller still needs the
            // event; a player error already published and emitted it (no double reporting).
            errorReported = true
            publish(stateMachine.onError())
            _events.tryEmit(PlaybackEvent.Error(result.error, fatal = true))
        }
        return result
    }

    // ---------------------------------------------------------------- player plumbing

    private fun ensurePlayer(): ExoPlayer = player ?: buildPlayer().also { player = it }

    private fun buildPlayer(): ExoPlayer {
        val t = tuning.normalized()
        // We are already ON the engine thread here (buildPlayer is only reached from the dispatcher),
        // so its Looper is the engine dispatcher's Looper — the one ExoPlayer must be driven from.
        // A dispatcher without a Looper fails here, loudly, instead of with "wrong thread" later.
        val looper = checkNotNull(Looper.myLooper()) {
            "Media3Engine needs a Looper-backed dispatcher (e.g. Handler(thread.looper).asCoroutineDispatcher()): " +
                "it serves as the ExoPlayer application looper"
        }
        val renderersFactory = if (t.preferPassthrough) {
            // S2's proven configuration: build the audio sink from the real device capabilities so
            // AC3/EAC3 can reach the HAL as a compressed bitstream instead of being forced to PCM.
            PassthroughRenderersFactory(appContext)
        } else {
            DefaultRenderersFactory(appContext)
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                t.minBufferMs,
                t.maxBufferMs,
                t.bufferForPlaybackMs,
                t.bufferForPlaybackAfterRebufferMs,
            )
            .build()
        val exo = ExoPlayer.Builder(appContext, renderersFactory)
            .setLooper(looper)
            .setTrackSelector(DefaultTrackSelector(appContext))
            .setLoadControl(loadControl)
            .build()
        exo.addListener(listener)
        // Audio focus / MediaSession belong to the foreground service (P1-7); the engine only
        // provides the sink. S1/S2 ran with the same setting.
        exo.setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ false)
        surface?.let { exo.setVideoSurface(it) }
        appliedPassthrough = t.preferPassthrough
        _events.tryEmit(PlaybackEvent.Capabilities(capabilities))
        return exo
    }

    /**
     * §7.6 step 2 ("关闭直通重试同一流") needs the audio path to actually change, and the sink is
     * fixed at player construction. A different preference therefore rebuilds the instance; the
     * default path never rebuilds (S5 single-instance reuse stays the norm).
     */
    private fun applyPassthrough(preferPassthrough: Boolean) {
        val current = appliedPassthrough
        if (current == null || current == preferPassthrough) return
        player?.release()
        player = null
        appliedPassthrough = null
    }

    private fun mediaSource(stream: Stream): MediaSource {
        val headers = buildMap {
            stream.referrer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
        }
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(tuning.normalized().connectTimeoutMs)
            .setReadTimeoutMs(tuning.normalized().readTimeoutMs)
            .setAllowCrossProtocolRedirects(true)
            .apply {
                stream.userAgent?.takeIf { it.isNotBlank() }?.let { setUserAgent(it) }
                if (headers.isNotEmpty()) setDefaultRequestProperties(headers)
            }
        return DefaultMediaSourceFactory(appContext)
            .setDataSourceFactory(dataSourceFactory)
            // §7.5: no double retry. The engine surfaces the first failure; `FailoverPolicy` owns the
            // retry/backoff/switch decision (§4.6, §6.2), so the internal fetch policy retries 0 times.
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(0))
            .createMediaSource(MediaItem.fromUri(stream.url))
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val phase = when (playbackState) {
                Player.STATE_BUFFERING -> PlayerPhase.BUFFERING
                Player.STATE_READY -> PlayerPhase.READY
                Player.STATE_ENDED -> PlayerPhase.ENDED
                else -> PlayerPhase.IDLE
            }
            publish(stateMachine.onPlayerPhase(phase))
            if (playbackState == Player.STATE_READY) onReady()
            if (playbackState == Player.STATE_ENDED) {
                _events.tryEmit(PlaybackEvent.Ended(EndReason.COMPLETED))
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val next = stateMachine.onIsPlaying(isPlaying)
            publish(next)
            if (isPlaying && !hasVideoTrack()) scheduleAudioOnlyFirstFrame()
        }

        override fun onRenderedFirstFrame() = emitFirstFrame()

        override fun onPlayerError(error: PlaybackException) {
            val appError = errorMapper.map(error)
            errorReported = true
            publish(stateMachine.onError())
            readyWaiter?.complete(AppResult.Err(appError))
            _events.tryEmit(PlaybackEvent.Error(appError, fatal = true))
        }

        override fun onTracksChanged(tracks: Tracks) {
            cachedTracks = audioTracksOf(tracks)
            cachedSelectedTrackId = selectedAudioTrackId(tracks)
            updateSnapshot()
            _events.tryEmit(PlaybackEvent.AudioTracks(cachedTracks, cachedSelectedTrackId))
        }
    }

    private fun onReady() {
        updateSnapshot()
        player?.let { exo ->
            cachedTracks = audioTracksOf(exo.currentTracks)
            cachedSelectedTrackId = selectedAudioTrackId(exo.currentTracks)
        }
        val media = preparedMedia()
        if (media != null) {
            readyWaiter?.complete(AppResult.Ok(media))
            _events.tryEmit(PlaybackEvent.Prepared(media, uptimeMs() - prepareStartUptimeMs))
        }
        if (!hasVideoTrack()) scheduleAudioOnlyFirstFrame()
    }

    /** A stream without a video track never fires `onRenderedFirstFrame` (S2 finding). */
    private fun scheduleAudioOnlyFirstFrame() {
        if (firstFrameEmitted) return
        scope.launch {
            delay(AUDIO_ONLY_FIRST_FRAME_DELAY_MS)
            val exo = player ?: return@launch
            if (!firstFrameEmitted && !hasVideoTrack() && exo.isPlaying && exo.currentPosition > 0) {
                emitFirstFrame()
            }
        }
    }

    private fun emitFirstFrame() {
        if (firstFrameEmitted || prepareStartUptimeMs <= 0L) return
        firstFrameEmitted = true
        updateSnapshot()
        val costMs = uptimeMs() - prepareStartUptimeMs
        _events.tryEmit(PlaybackEvent.FirstFrame(costMs))
    }

    private fun updateSnapshot() {
        val exo = player ?: return
        val video = exo.videoFormat
        val audio = exo.audioFormat
        snapshotCache = PlaybackSnapshot(
            videoCodec = video?.sampleMimeType,
            audioCodec = audio?.sampleMimeType,
            width = video?.width?.takeIf { it > 0 } ?: 0,
            height = video?.height?.takeIf { it > 0 } ?: 0,
            audioPath = AudioPathClassifier.of(audio?.sampleMimeType),
        )
    }

    private fun preparedMedia(): PreparedMedia? {
        val exo = player ?: return null
        val video = exo.videoFormat
        val audio = exo.audioFormat
        val duration = exo.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        return PreparedMedia(
            streamId = activeStreamId,
            durationMs = duration,
            isLive = exo.isCurrentMediaItemLive,
            videoCodec = video?.sampleMimeType,
            audioCodec = audio?.sampleMimeType,
            width = video?.width?.takeIf { it > 0 } ?: 0,
            height = video?.height?.takeIf { it > 0 } ?: 0,
            audioTracks = cachedTracks,
        )
    }

    private fun hasVideoTrack(): Boolean =
        player?.currentTracks?.groups?.any { it.type == C.TRACK_TYPE_VIDEO && it.length > 0 } == true

    private fun audioTracksOf(tracks: Tracks): List<AudioTrackInfo> {
        val result = mutableListOf<AudioTrackInfo>()
        var audioGroupIndex = -1
        tracks.groups.forEach { group ->
            if (group.type != C.TRACK_TYPE_AUDIO) return@forEach
            audioGroupIndex++
            for (trackIndex in 0 until group.length) {
                val format = group.getTrackFormat(trackIndex)
                result += AudioTrackInfo(
                    id = audioTrackId(audioGroupIndex, trackIndex),
                    label = labelOf(format, trackIndex),
                    language = format.language,
                    codec = format.sampleMimeType ?: "",
                    isDefault = group.isTrackSelected(trackIndex),
                )
            }
        }
        return result
    }

    private fun selectedAudioTrackId(tracks: Tracks): String? {
        var audioGroupIndex = -1
        tracks.groups.forEach { group ->
            if (group.type != C.TRACK_TYPE_AUDIO) return@forEach
            audioGroupIndex++
            for (trackIndex in 0 until group.length) {
                if (group.isTrackSelected(trackIndex)) return audioTrackId(audioGroupIndex, trackIndex)
            }
        }
        return null
    }

    private fun labelOf(format: Format, trackIndex: Int): String =
        format.label ?: format.language ?: "Track ${trackIndex + 1}"

    private fun audioTrackId(groupIndex: Int, trackIndex: Int) = "$AUDIO_ID_PREFIX$groupIndex:$trackIndex"

    private fun parseAudioTrackId(id: String): Pair<Int, Int>? {
        if (!id.startsWith(AUDIO_ID_PREFIX)) return null
        val parts = id.removePrefix(AUDIO_ID_PREFIX).split(':')
        val group = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val track = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return group to track
    }

    private fun publish(next: EngineState) {
        _state.value = next
    }

    /** Thread-safe "what is playing" for the telemetry bridge (docs/02 §7.7). */
    fun snapshot(): PlaybackSnapshot = snapshotCache

    /** Selected audio track id as last observed on the engine thread. */
    fun selectedAudioTrackId(): String? = cachedSelectedTrackId

    /** `preferPassthrough=true` equivalent of the S2 fixture: the sink is built from device capabilities. */
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

    private companion object {
        const val AUDIO_ID_PREFIX = "audio:"

        /** S2 needed a settle window before an audio-only stream could be judged as running. */
        const val AUDIO_ONLY_FIRST_FRAME_DELAY_MS = 250L
    }
}
