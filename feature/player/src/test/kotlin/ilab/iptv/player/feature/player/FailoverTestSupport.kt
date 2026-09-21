package ilab.iptv.player.feature.player

import ilab.iptv.player.core.common.AppError
import ilab.iptv.player.core.common.AppResult
import ilab.iptv.player.core.common.Clock
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.LogEvent
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.domain.playback.DefaultFailoverPolicy
import ilab.iptv.player.core.domain.playback.FailoverLimits
import ilab.iptv.player.core.domain.playback.FailoverTuning
import ilab.iptv.player.core.domain.playback.PlaybackWatchdog
import ilab.iptv.player.core.domain.playback.WatchdogConfig
import ilab.iptv.player.core.model.Channel
import ilab.iptv.player.core.model.ChannelGroup
import ilab.iptv.player.core.model.PlaybackPhase
import ilab.iptv.player.core.model.PlaybackUiState
import ilab.iptv.player.core.model.PreparedMedia
import ilab.iptv.player.core.model.Stream
import ilab.iptv.player.core.model.StreamHealth
import ilab.iptv.player.core.player.EngineSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler

/**
 * Shared test doubles for the P1-5 fail-over wiring.
 *
 * The wiring is tested through [PlaybackPort] rather than through `PlaybackSession` because the
 * session is Android-bound (Context/HandlerThread/ExoPlayer) and the point of this work package is
 * the *sequence of policy calls and playback commands*, which is exactly what a port-shaped fake can
 * record. The policy, the watchdog and the limiter under test are the real ones from `:core:domain`.
 */

/** A clock that reads the coroutine test scheduler, so virtual time and `nowMs()` never disagree. */
class SchedulerClock(private val scheduler: TestCoroutineScheduler) : Clock {
    override fun nowMs(): Long = scheduler.currentTime
}

fun prepared(streamId: Long): AppResult<PreparedMedia> = AppResult.Ok(
    PreparedMedia(
        streamId = streamId,
        durationMs = null,
        isLive = true,
        videoCodec = "video/avc",
        audioCodec = "audio/mp4a-latm",
        width = 1920,
        height = 1080,
        audioTracks = emptyList(),
    ),
)

fun channel(id: Long, groupKey: String = "cctv"): Channel = Channel(
    id = id,
    name = "CH$id",
    nameKey = "ch$id",
    tvgId = null,
    group = ChannelGroup.CCTV,
    groupKey = groupKey,
    groupTitle = groupKey,
    logoUrl = null,
    channelNo = id.toInt(),
    favorite = false,
    hidden = false,
    sortOrder = id.toInt(),
    epgChannelId = null,
    epgMatch = ilab.iptv.player.core.model.EpgMatchType.NONE,
    streamCount = 1,
)

fun stream(id: Long, channelId: Long = 1, score: Int = 50, disabled: Boolean = false): Stream = Stream(
    id = id,
    channelId = channelId,
    url = "http://example.invalid/$id.m3u8",
    userAgent = null,
    referrer = null,
    sourceId = "src-a",
    quality = null,
    videoCodec = null,
    audioCodec = null,
    width = 1920,
    height = 1080,
    score = score,
    priority = 0,
    lastOkAtMs = null,
    lastCheckAtMs = null,
    failCount = 0,
    lastError = null,
    disabled = disabled,
)

/** One `AppResult` per (streamId, attempt); anything not scripted prepares successfully. */
class FakePlaybackPort : PlaybackPort {

    data class Prepare(
        val channelId: Long,
        val streamId: Long,
        val attempt: Int,
        val preferPassthrough: Boolean,
        val atMs: Long,
    )

    val prepares = mutableListOf<Prepare>()
    val switchedTo = mutableListOf<Long>()
    val runningHints = mutableListOf<String>()
    val exhausted = mutableListOf<String>()

    var responses: (Long, Int) -> AppResult<PreparedMedia> = { id, _ -> prepared(id) }
    var samples: () -> EngineSample = { EngineSample(0, 0, false) }
    var now: () -> Long = { 0L }
    var stoppedReason: String? = null

    /** How long a prepare takes in virtual time; a channel-switch cost needs a non-zero number. */
    var prepareDelayMs: Long = 0L

    private val _state = MutableStateFlow(PlaybackUiState.EMPTY)
    override val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    override suspend fun watch(
        channel: Channel,
        stream: Stream,
        attempt: Int,
        preferPassthrough: Boolean,
    ): AppResult<PreparedMedia> {
        prepares += Prepare(channel.id, stream.id, attempt, preferPassthrough, now())
        if (prepareDelayMs > 0) kotlinx.coroutines.delay(prepareDelayMs)
        val result = responses(stream.id, attempt)
        _state.value = when (result) {
            is AppResult.Ok -> PlaybackUiState.EMPTY.copy(
                channelId = channel.id,
                activeStreamId = stream.id,
                phase = PlaybackPhase.PLAYING,
            )

            is AppResult.Err -> PlaybackUiState.EMPTY.copy(
                channelId = channel.id,
                activeStreamId = stream.id,
                phase = PlaybackPhase.ERROR,
                lastError = result.error,
            )
        }
        return result
    }

    override suspend fun sample(): EngineSample = samples()

    override fun stop(reason: String) {
        stoppedReason = reason
        _state.value = _state.value.copy(phase = PlaybackPhase.STOPPED)
    }

    override fun onFailoverRunning(hint: String) {
        runningHints += hint
        _state.value = _state.value.copy(phase = PlaybackPhase.FAILOVER)
    }

    override fun onFailoverSwitched(toStreamId: Long, hint: String) {
        switchedTo += toStreamId
        _state.value = _state.value.copy(
            activeStreamId = toStreamId,
            phase = PlaybackPhase.BUFFERING,
            failoverCount = _state.value.failoverCount + 1,
        )
    }

    override fun onFailoverExhausted(error: AppError?, message: String) {
        exhausted += message
        _state.value = _state.value.copy(
            phase = PlaybackPhase.ERROR,
            lastError = error,
            errorText = message,
            infoBar = _state.value.infoBar?.copy(failoverHint = message),
        )
    }
}

/** Candidates and health the policy sees, plus the on-demand re-probe lever of the PLAYLIST path. */
class FakeFailoverCatalog : FailoverCatalog {

    var candidatesByChannel: Map<Long, List<Stream>> = emptyMap()
    var healthByStream: Map<Long, StreamHealth> = emptyMap()
    var reprobeResult: Map<Long, List<Stream>> = emptyMap()
    val reprobed = mutableListOf<Long>()

    override suspend fun candidates(channelId: Long): List<Stream> =
        candidatesByChannel[channelId].orEmpty()

    override suspend fun health(streamIds: List<Long>): Map<Long, StreamHealth> =
        healthByStream.filterKeys { it in streamIds }

    override suspend fun reprobe(channelId: Long): List<Stream> {
        reprobed += channelId
        return reprobeResult[channelId] ?: candidatesByChannel[channelId].orEmpty()
    }
}

/** Captures the `PLAY_*` codes and fields the wiring emits (docs/03 §3.3). */
class RecordingLogger : Logger {

    data class Entry(val code: String, val fields: Map<String, Any?>)

    val entries = mutableListOf<Entry>()

    fun codes(): List<String> = entries.map { it.code }

    fun first(code: String): Entry? = entries.firstOrNull { it.code == code }

    fun all(code: String): List<Entry> = entries.filter { it.code == code }

    override fun log(event: LogEvent) {
        entries += Entry(event.code, event.fields)
    }

    override fun v(category: LogCategory, code: String, message: String, fields: Map<String, Any?>) =
        record(code, fields)

    override fun d(category: LogCategory, code: String, message: String, fields: Map<String, Any?>) =
        record(code, fields)

    override fun i(category: LogCategory, code: String, message: String, fields: Map<String, Any?>) =
        record(code, fields)

    override fun w(
        category: LogCategory,
        code: String,
        message: String,
        fields: Map<String, Any?>,
        error: Throwable?,
    ) = record(code, fields)

    override fun e(
        category: LogCategory,
        code: String,
        message: String,
        fields: Map<String, Any?>,
        error: Throwable?,
    ) = record(code, fields)

    override fun flush(timeoutMs: Long) = Unit

    private fun record(code: String, fields: Map<String, Any?>) {
        entries += Entry(code, fields)
    }
}

/** One wired-up system under test: fake playback + catalog, the REAL policy, watchdog and limiter. */
class FailoverHarness(
    scope: CoroutineScope,
    scheduler: TestCoroutineScheduler,
    limits: FailoverLimits = FailoverLimits(),
    tuning: FailoverTuning = FailoverTuning(),
    tickMs: Long = 250L,
) {
    val clock = SchedulerClock(scheduler)
    val port = FakePlaybackPort()
    val catalog = FakeFailoverCatalog()
    val logger = RecordingLogger()
    val policy = DefaultFailoverPolicy(tuning)
    val watchdog = PlaybackWatchdog(
        clock = clock,
        config = WatchdogConfig(
            prepareTimeoutMs = 12_000,
            progressThresholdMs = limits.stallThresholdMs,
            bufferingThresholdMs = limits.stallThresholdMs,
        ),
    )
    val coordinator = PlaybackFailoverCoordinator(
        port = port,
        policy = policy,
        catalog = catalog,
        watchdog = watchdog,
        clock = clock,
        logger = logger,
        scope = scope,
        limits = limits,
        tickMs = tickMs,
    )

    init {
        port.now = { scheduler.currentTime }
        // Default: the stream is progressing, so only the tests that say otherwise see a stall.
        port.samples = { EngineSample(scheduler.currentTime, scheduler.currentTime, false) }
    }
}

/** An error of each class, through the frozen factories (docs/02 §4.1). */
fun failure(failureClass: ilab.iptv.player.core.common.FailureClass): AppError = when (failureClass) {
    ilab.iptv.player.core.common.FailureClass.HTTP_CLIENT -> AppError.http(404, EventCodes.PLAY_PREPARE_FAIL)
    ilab.iptv.player.core.common.FailureClass.HTTP_SERVER -> AppError.http(503, EventCodes.PLAY_PREPARE_FAIL)
    ilab.iptv.player.core.common.FailureClass.TIMEOUT -> AppError.timeout(EventCodes.PLAY_PREPARE_FAIL)
    ilab.iptv.player.core.common.FailureClass.NET_UNREACHABLE -> AppError.network(EventCodes.PLAY_PREPARE_FAIL)
    ilab.iptv.player.core.common.FailureClass.NO_CAPABILITY -> AppError.capability(EventCodes.PLAY_PREPARE_FAIL)
    ilab.iptv.player.core.common.FailureClass.STORAGE -> AppError.storage(EventCodes.DB_FAIL)
    ilab.iptv.player.core.common.FailureClass.PERMISSION -> AppError.permission(EventCodes.PLAY_PREPARE_FAIL)
    ilab.iptv.player.core.common.FailureClass.CANCELLED -> AppError.cancelled(EventCodes.PLAY_END)
    else -> AppError.unknown(EventCodes.PLAY_PREPARE_FAIL)
}
