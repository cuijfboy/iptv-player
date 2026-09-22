package ilab.iptv.player.core.player

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.media3.common.Player
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ilab.iptv.player.core.common.AppError
import ilab.iptv.player.core.common.AppResult
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.common.SessionIdFactory
import ilab.iptv.player.core.model.AspectRatioMode
import ilab.iptv.player.core.model.Channel
import ilab.iptv.player.core.model.DeviceProfile
import ilab.iptv.player.core.model.EngineCapability
import ilab.iptv.player.core.model.EngineState
import ilab.iptv.player.core.model.InfoBarState
import ilab.iptv.player.core.model.NowNext
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
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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
 * `PLAY_*` telemetry and the state machine all stay. P1-5 did exactly that: the fail-over wiring
 * (`PlaybackFailoverCoordinator` in `:feature:player`, the only module allowed to see BOTH
 * `:core:player` and `:core:domain`) drives this session, which remains the single writer of
 * `PlaybackUiState` (§4.5 C1) through the narrow hooks below.
 *
 * WHAT IT DOES NOT DO (boundaries): it makes no fail-over decision of its own (the policy lives in
 * `:core:domain`, the wiring in `:feature:player`); no MediaSession / audio focus / foreground
 * service (P1-7); no View of any kind (§7.4: the engine never creates or owns a View).
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

    /**
     * The engine's own phase, mirrored read-only for the foreground service (P1-7).
     *
     * The notification has to say whether the stream is playing or the user paused it, and that
     * distinction does not exist in [PlaybackUiState] (a paused ExoPlayer and a buffering one are both
     * "buffering" there). It is a *read* of the engine, not a second writer of UI state, so C1 holds:
     * the state machine below is still the only thing that writes `PlaybackUiState`.
     */
    val engineState: StateFlow<EngineState> = engine.state

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

    /**
     * The `Player` for the `MediaSession` of the foreground service (P1-7 item 2), or `null` when the
     * engine cannot offer one.
     *
     * The player may only be touched on the engine thread, and `MediaSessionService.onGetSession` runs
     * on the main thread, so this hops over and waits. The wait is bounded: an engine busy with a
     * 12 s `prepare` must not block the service's main thread indefinitely — the caller logs and runs
     * without a session rather than hanging.
     */
    fun mediaSessionPlayer(timeoutMs: Long = MEDIA_SESSION_TIMEOUT_MS): Player? {
        val media3 = engine as? Media3Engine ?: return null
        val latch = CountDownLatch(1)
        val holder = AtomicReference<Player?>(null)
        scope.launch {
            holder.set(media3.mediaPlayer())
            latch.countDown()
        }
        return try {
            if (latch.await(timeoutMs, TimeUnit.MILLISECONDS)) holder.get() else null
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    /**
     * Audio-focus ducking (P1-7 item 3): the service asks the controller, the controller asks the
     * engine. `true` → `AudioFocusPolicy.duckLevel`, `false` → full volume. The engine owns the
     * player, so this is the only route to `ExoPlayer.volume` (§4.5 C2: one caller).
     */
    fun setDucked(ducked: Boolean) {
        val media3 = engine as? Media3Engine ?: return
        media3.setVolume(if (ducked) AudioFocusPolicy.DEFAULT_DUCK_VOLUME else AudioFocusPolicy.FULL_VOLUME)
    }

    // ---------------------------------------------------------------- commands

    /**
     * Starts (or switches to) [stream] of [channel] and waits until the engine is ready.
     *
     * The engine's own result comes back to the caller; the UI state is updated either way,
     * including the readable failure text of P1-4 item 3.
     */
    suspend fun watch(
        channel: Channel,
        stream: Stream,
        attempt: Int = 1,
        preferPassthrough: Boolean = tuning.normalized().preferPassthrough,
        timeoutMs: Long = 0L,
    ): AppResult<PreparedMedia> =
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
                    // P2-7: the channel starts with no programme line and gains it when the EPG lookup
                    // answers (`onNowNext`). Resolving it here would put a database round trip inside
                    // `watch`, which is the path that must reach the first frame as fast as possible.
                    nowNext = null,
                ),
            )
            val request = PlaybackRequest(
                channelId = channel.id,
                stream = stream,
                // §4.2 `PlaybackRequest.timeoutMs`. SWITCH-P95-1 passes an explicit window for the
                // first attempt of a channel that has a backup; `0` keeps the §7.5 tuning default.
                timeoutMs = if (timeoutMs > 0) timeoutMs else tuning.normalized().prepareTimeoutMs,
                // P1-5 wiring, docs/02 §7.6 step 2: the NO_CAPABILITY row retries the same stream
                // with the compressed bitstream handed over disabled; every other path keeps the
                // tuning default.
                preferPassthrough = preferPassthrough,
                sessionId = sessionId,
            )
            // P1-7: the MediaSession publishes this as now-playing metadata. Written before the
            // engine builds the `MediaItem` (the two are ordered by this call and `prepare`).
            (engine as? Media3Engine)?.setNowPlayingTitle(channel.name)
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

    /**
     * Fault-tolerant read of the engine's live position/buffering, for the fail-over watchdog
     * (docs/02 §6.2). `ExoPlayer` may only be read on its application looper, which is exactly the
     * session's engine dispatcher (§4.5 C2), so this hops there instead of touching the player.
     */
    suspend fun sample(): EngineSample = withContext(dispatcher) {
        (engine as? Media3Engine)?.sample() ?: EngineSample.EMPTY
    }

    // ---------------------------------------------------------------- fail-over presentation (P1-5)

    /**
     * A decision is being applied. The controller (P1-5's fail-over wiring) calls this when it asks
     * the policy and is about to retry or switch (docs/02 §4.5 C1 "发生切换决策时置 FAILOVER").
     */
    fun onFailoverRunning(hint: String) {
        machine.onFailoverRunning(hint)
    }

    /** The switch took effect: what the info bar shows and what `PLAY_END` reports as failoverCount. */
    fun onFailoverSwitched(toStreamId: Long, hint: String) {
        machine.onFailoverSwitched(toStreamId, hint)
    }

    /** No candidate left: the channel stays unavailable and the screen shows the policy's message. */
    fun onFailoverExhausted(error: AppError?, message: String) {
        machine.onFailoverExhausted(error, message)
    }

    /**
     * P2-7 item 4: the info bar's now/next line. Called by the player screen once the EPG lookup for
     * the current channel answers (and again after a channel switch). The session is still the only
     * writer of `PlaybackUiState` (§4.5 C1) — the ViewModel does not touch the bar itself, it hands the
     * data to the one machine that does.
     */
    fun onNowNext(nowNext: NowNext?) {
        machine.onNowNext(nowNext)
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

    /**
     * Selected audio track (P3-3 item 1). The engine applies it without re-preparing the stream, so
     * the switch is heard immediately; the track list, the chosen row and the on-screen label come
     * back through [state], because this call only confirms the id is known.
     *
     * The `PLAY_*` line is emitted here rather than in the engine: docs/02 §7.7 makes the controller
     * the only writer of playback telemetry. See [PlaybackEventLogger.onTrackSelected] for why the
     * code is the re-used `PLAY_FIRST_FRAME`.
     */
    fun selectAudioTrack(id: String?): Boolean {
        val known = engine.selectAudioTrack(id)
        if (known) {
            val track = engine.audioTracks().firstOrNull { it.id == id }
            telemetry.onTrackSelected(
                kind = "audio",
                id = id,
                label = track?.label,
                enabled = true,
                audioCodec = snapshot().audioCodec,
            )
            machine.onAudioTracks(engine.audioTracks(), id)
        }
        return known
    }

    /**
     * Subtitle selection (P3-3 item 2): one text track, or `null` for 「关闭」.
     *
     * The text renderer lives next to the ExoPlayer instance, so this is a `Media3Engine` method
     * reached through the same narrow bridge as [setDucked] / [mediaSessionPlayer] — nothing is added
     * to the frozen [PlayerEngine] interface (docs/02 §4.4 v1 stays additive-free here).
     */
    fun selectSubtitleTrack(id: String?): Boolean {
        val media3 = engine as? Media3Engine ?: return false
        val known = if (id == null) {
            media3.setSubtitlesEnabled(false)
            true
        } else {
            val applied = media3.selectSubtitleTrack(id)
            if (applied) media3.setSubtitlesEnabled(true)
            applied
        }
        if (known) {
            val label = media3.subtitleTracks().firstOrNull { it.id == id }?.label
            telemetry.onTrackSelected(
                kind = "subtitle",
                id = id,
                label = label,
                enabled = id != null,
                audioCodec = snapshot().audioCodec,
            )
        }
        return known
    }

    /** P3-3 item 2's plain on/off switch, leaving the remembered track alone. */
    fun setSubtitlesEnabled(enabled: Boolean) {
        val media3 = engine as? Media3Engine ?: return
        media3.setSubtitlesEnabled(enabled)
        telemetry.onTrackSelected(
            kind = "subtitle",
            id = media3.selectedSubtitleTrackId(),
            label = null,
            enabled = enabled,
            audioCodec = snapshot().audioCodec,
        )
    }

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

            is PlaybackEvent.SubtitleTracks ->
                machine.onSubtitleTracks(event.tracks, event.selectedId, event.enabled)

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

        /**
         * How long the foreground service waits for the engine's player before giving up on the
         * `MediaSession` (P1-7). Creating the instance is ~4 ms (S5); the budget is for an engine that
         * is currently inside a `prepare`.
         */
        const val MEDIA_SESSION_TIMEOUT_MS = 3_000L

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
