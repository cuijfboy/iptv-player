package ilab.iptv.player.core.player

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ilab.iptv.player.core.common.AppResult
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.common.SessionIdFactory
import ilab.iptv.player.core.model.AspectRatioMode
import ilab.iptv.player.core.model.Channel
import ilab.iptv.player.core.model.DeviceProfile
import ilab.iptv.player.core.model.EngineCapability
import ilab.iptv.player.core.model.InfoBarState
import ilab.iptv.player.core.model.PlaybackEvent
import ilab.iptv.player.core.model.PlaybackRequest
import ilab.iptv.player.core.model.PlaybackUiState
import ilab.iptv.player.core.model.PreparedMedia
import ilab.iptv.player.core.model.Quality
import ilab.iptv.player.core.model.Stream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Singleton

/**
 * The process-wide playback session (docs/02 §7.2 P1–P5, §4.5 C1/C2): it owns the ONE engine
 * instance, drives it on the engine dispatcher, and is the only writer of the business state the
 * player screen renders.
 *
 * RELATION TO THE FROZEN PORT: docs/02 §7.2 P2 calls this role `PlaybackController` and puts it in
 * `:core:player`. That is exactly where this class lives; it is named `PlaybackSession` because the
 * domain port (`PlaybackCommand` + `PlaybackController`, docs/02 §4.3) also carries failover and
 * audio-focus duties that P1-6/P1-7 own, and promising them here would be a lie. When P1-6 lands,
 * `PlaybackController` wraps this session instead of replacing it — the engine plumbing, the
 * `PLAY_*` telemetry and the state machine all stay.
 *
 * WHAT IT DOES NOT DO (boundaries): no failover decision (P1-6), no watchdog (P1-6), no MediaSession
 * / audio focus / foreground service (P1-7), no channel-switching keymap (P1-5), no View of any kind
 * (§7.4: the engine never creates or owns a View).
 *
 * THREADING (§4.5 C2): a single HandlerThread is both the engine dispatcher and ExoPlayer's
 * application looper — the requirement P1-3 measured (a plain dispatcher fails at `buildPlayer`).
 */
@Singleton
class PlaybackSession(
    context: Context,
    private val logger: Logger,
    private val sessionIds: SessionIdFactory,
    private val tuning: EngineTuning = EngineTuning.LIVE_DEFAULT,
    factory: PlayerEngineFactory = Media3EngineFactory(context.applicationContext, tuning),
) {

    private val appContext: Context = context.applicationContext
    private val engineThread = HandlerThread(ENGINE_THREAD_NAME).apply { start() }
    private val dispatcher = Handler(engineThread.looper).asCoroutineDispatcher()
    private val scope = CoroutineScope(dispatcher + SupervisorJob())

    /** docs/02 §7.1: selection by capabilities, never by hard-coded class. */
    private val selector = DefaultEngineSelector(listOf(factory))

    /** §7.1: "找不到满足能力的引擎 → AppError.capability(...)" — fail loudly, never silently degrade. */
    private val selectedFactory: PlayerEngineFactory = checkNotNull(
        selector.selectOrNull(REQUIRED_CAPS, deviceProfile(appContext)),
    ) { "no registered engine satisfies $REQUIRED_CAPS" }

    private val engine: PlayerEngine = selectedFactory.create(dispatcher)

    private val sessionId: String = sessionIds.newId("play")
    private val telemetry = PlaybackEventLogger(logger, sessionId)
    private val machine = PlaybackUiStateMachine()
    private val watchMutex = Mutex()

    private var channel: Channel? = null
    private var stream: Stream? = null
    private var attempt = 0
    private var released = false
    private var lastPrepared: PreparedMedia? = null

    /** The UI's single input (docs/02 §4.5 C1). */
    val state: StateFlow<PlaybackUiState> = machine.state

    init {
        telemetry.onEngineInit(engine.id, engine.capabilities)
        // The engine's raw state is translated here and nowhere else: a View must never subscribe to
        // an engine (docs/02 §4.5 C1).
        scope.launch { engine.state.collect { machine.onEngineState(it) } }
        scope.launch { engine.events.collect { event -> onEngineEvent(event) } }
    }

    // ---------------------------------------------------------------- surface (§7.4)

    /**
     * docs/02 §7.2 P3: entering/leaving the player page never creates or releases the engine; the
     * page only hands its surface over. `null` = stop rendering, keep the audio.
     */
    fun attachSurface(surface: Surface?) {
        engine.attach(surface)
    }

    // ---------------------------------------------------------------- commands

    /**
     * Starts (or switches to) [stream] of [channel] and waits until the engine is ready.
     *
     * The engine's own result comes back to the caller; the UI state is updated either way,
     * including the readable failure text of P1-4 item 3.
     */
    suspend fun watch(channel: Channel, stream: Stream, attempt: Int = 1): AppResult<PreparedMedia> =
        watchMutex.withLock {
            this.channel = channel
            this.stream = stream
            this.attempt = attempt
            lastPrepared = null
            machine.onWatchStarted(
                channelId = channel.id,
                streamId = stream.id,
                infoBar = InfoBarState(
                    channelName = channel.name,
                    logoUrl = channel.logoUrl,
                    qualityLabel = streamQualityLabel(stream),
                    // docs/04: EPG now/next is P2-7. The placeholder is carried on purpose, so the
                    // bar's layout is proven with the field it will actually show.
                    nowNext = null,
                ),
            )
            val request = PlaybackRequest(
                channelId = channel.id,
                stream = stream,
                timeoutMs = tuning.normalized().prepareTimeoutMs,
                preferPassthrough = tuning.normalized().preferPassthrough,
                sessionId = sessionId,
            )
            telemetry.onPrepareStart(engine.id, request, attempt)
            val result = engine.prepare(request)
            when (result) {
                is AppResult.Ok -> machine.onPrepared(result.value)
                is AppResult.Err -> {
                    telemetry.onPrepareFail(engine.id, result.error, attempt)
                    machine.onError(result.error)
                }
            }
            result
        }

    /** Retry entry point of the failure overlay: same channel, same stream, one attempt later. */
    suspend fun retry(): AppResult<PreparedMedia>? {
        val currentChannel = channel ?: return null
        val currentStream = stream ?: return null
        machine.onRetryRequested()
        return watch(currentChannel, currentStream, attempt = attempt + 1)
    }

    fun play() {
        engine.play()
    }

    fun pause() {
        engine.pause()
    }

    /**
     * Ends the playback session (docs/02 §7.2 P4/P5). The engine instance survives — S5 measured
     * that reuse is the point, and only a real session end may release it.
     */
    fun stop(reason: String = "user") {
        engine.stop()
        machine.onStopped()
        logger.i(
            LogCategory.PLAYER,
            EventCodes.PLAY_END,
            "playback session stopped",
            mapOf(
                "channelId" to channel?.id,
                "streamId" to stream?.id,
                "reason" to reason,
                "engine" to engine.id,
                "sessionId" to sessionId,
            ),
        )
    }

    fun setAspectRatio(mode: AspectRatioMode) {
        engine.setAspectRatio(mode)
        machine.onAspectRatio(mode)
    }

    /** Selected audio track; P3-3 owns the track-switching UI, the call already works. */
    fun selectAudioTrack(id: String?): Boolean = engine.selectAudioTrack(id)

    /** Releases the engine for good (docs/02 §7.2 P5). Called when the session really ends. */
    fun release() {
        if (released) return
        released = true
        engine.release()
        telemetry.onEngineRelease(engine.id)
        machine.onReleased()
        engineThread.quitSafely()
    }

    // ---------------------------------------------------------------- engine events (§7.7)

    private fun onEngineEvent(event: PlaybackEvent) {
        when (event) {
            is PlaybackEvent.FirstFrame -> {
                telemetry.onFirstFrame(engine.id, event, snapshot())
                machine.onFirstFrame(event.costMs)
            }

            is PlaybackEvent.Stalled -> telemetry.onStalled(engine.id, event)

            is PlaybackEvent.Capabilities -> telemetry.onEngineInit(engine.id, event.caps)

            is PlaybackEvent.Prepared -> lastPrepared = event.media

            is PlaybackEvent.AudioTracks -> machine.onAudioTracks(event.tracks, event.selectedId)

            // A failure AFTER the first frame is a session failure; `PLAY_PREPARE_FAIL` means
            // "起播失败" (docs/03 §3.3), so the state carries it and P1-6's policy is the one that
            // will log `PLAY_FAILOVER`.
            is PlaybackEvent.Error -> machine.onError(event.error)

            is PlaybackEvent.Ended -> machine.onStopped()
        }
    }

    /**
     * The engine's codec/resolution/audio-path read, used to enrich `PLAY_FIRST_FRAME` (§7.7).
     *
     * The engine's own cache wins, and that ordering is a measured finding, not a preference: on the
     * device the first frame is rendered **before** the ready callback lands, so a session that only
     * filled this from `PlaybackEvent.Prepared` logged `vcodec=null` for a 1080p H.264 stream
     * (docs/05 §16.6). `Media3Engine.snapshot()` is refreshed on `onTracksChanged` — earlier than both
     * — and is documented as safe to read off the engine thread. The prepared-media path below stays
     * as the fallback for an engine implementation that does not offer that read.
     */
    private fun snapshot(): PlaybackSnapshot {
        val cached = (engine as? Media3Engine)?.snapshot()
        if (cached != null && cached != PlaybackSnapshot.EMPTY) return cached
        val media = lastPrepared ?: return cached ?: PlaybackSnapshot.EMPTY
        return PlaybackSnapshot(
            videoCodec = media.videoCodec,
            audioCodec = media.audioCodec,
            width = media.width,
            height = media.height,
            audioPath = AudioPathClassifier.of(media.audioCodec),
        )
    }

    private companion object {
        const val ENGINE_THREAD_NAME = "playback-engine"

        /** Capabilities an IPTV stream needs before we even try: the two transports of the baseline. */
        val REQUIRED_CAPS: Set<EngineCapability> = setOf(EngineCapability.HLS, EngineCapability.HTTP_TS)

        /** "720p H.264" as the stream row knows it, before the engine confirms the real resolution. */
        fun streamQualityLabel(stream: Stream): String? {
            val resolution = when (stream.quality) {
                Quality.UHD_4K -> "2160p"
                Quality.FHD_1080 -> "1080p"
                Quality.HD_720 -> "720p"
                Quality.SD -> "SD"
                Quality.UNKNOWN, null -> null
            } ?: stream.height.takeIf { it > 0 }?.let { "${it}p" }
            val codec = stream.videoCodec?.substringAfterLast('/')
            return listOfNotNull(resolution, codec).takeIf { it.isNotEmpty() }?.joinToString(" ")
        }

        /**
         * docs/02 §4.7/§7.4: the profile is probed, never hard-coded per model. Only the fields the
         * selector looks at today are filled from the running device.
         */
        fun deviceProfile(context: Context): DeviceProfile {
            val memoryInfo = ActivityManager.MemoryInfo()
            (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
                ?.getMemoryInfo(memoryInfo)
            return DeviceProfile(
                abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
                sdk = Build.VERSION.SDK_INT,
                ramMb = (memoryInfo.totalMem / (1024 * 1024)).toInt(),
                audioPassthrough = emptySet(),
                maxWidth = 1_920,
                maxHeight = 1_080,
                maxFrameRate = 60f,
            )
        }
    }
}

/**
 * Hilt wiring for the session. It lives in `:core:player` because that is the only module allowed to
 * declare the engine (docs/02 §3.2 rule 4); `:feature:player` consumes it through this binding.
 */
@Module
@InstallIn(SingletonComponent::class)
object PlayerModule {

    @Provides
    @Singleton
    fun providePlaybackSession(
        @ApplicationContext context: Context,
        logger: Logger,
        sessionIds: SessionIdFactory,
    ): PlaybackSession = PlaybackSession(
        context = context,
        logger = logger,
        sessionIds = sessionIds,
    )
}
