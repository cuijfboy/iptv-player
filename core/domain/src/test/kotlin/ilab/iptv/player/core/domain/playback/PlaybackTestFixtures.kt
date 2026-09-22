package ilab.iptv.player.core.domain.playback

import ilab.iptv.player.core.common.AppError
import ilab.iptv.player.core.common.Clock
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.FailureClass
import ilab.iptv.player.core.model.Stream
import ilab.iptv.player.core.model.StreamHealth

/** A clock the test drives by hand; the watchdog never reads wall time on its own. */
class MutableClock(var nowMs: Long = 0L) : Clock {
    override fun nowMs(): Long = nowMs
}

fun stream(
    id: Long,
    channelId: Long = 1,
    score: Int = 0,
    priority: Int = 0,
    lastOkAtMs: Long? = null,
    lastCheckAtMs: Long? = null,
    failCount: Int = 0,
    lastError: String? = null,
    disabled: Boolean = false,
): Stream = Stream(
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
    priority = priority,
    lastOkAtMs = lastOkAtMs,
    lastCheckAtMs = lastCheckAtMs,
    failCount = failCount,
    lastError = lastError,
    disabled = disabled,
)

fun health(
    consecutiveFails: Int,
    lastOkAtMs: Long? = null,
    attempts: Int = consecutiveFails,
    failures: Int = consecutiveFails,
): StreamHealth = StreamHealth(
    attempts = attempts,
    failures = failures,
    lastOkAtMs = lastOkAtMs,
    consecutiveFails = consecutiveFails,
)

/*
 * One `AppError` per `FailureClass`, always built through the frozen factories (docs/02 §4.1:
 * business code must not pick a class by hand). This is also what makes the "plan.retryable ==
 * AppError.retryable" cross-check in `FailureClassPolicyTest` meaningful.
 */
fun failure(failureClass: FailureClass, code: String = EventCodes.PLAY_PREPARE_FAIL): AppError =
    when (failureClass) {
        FailureClass.HTTP_CLIENT -> AppError.http(404, code)
        FailureClass.HTTP_SERVER -> AppError.http(503, code)
        FailureClass.TIMEOUT -> AppError.timeout(code)
        FailureClass.NET_UNREACHABLE -> AppError.network(code)
        FailureClass.TLS -> AppError.tls(code)
        FailureClass.PARSE -> AppError.parse(code)
        FailureClass.DECODE_UNSUPPORTED -> AppError.decode(code, unsupported = true)
        FailureClass.DECODE_CORRUPT -> AppError.decode(code, unsupported = false)
        FailureClass.EMPTY_MEDIA -> AppError.emptyMedia(code)
        FailureClass.PLAYLIST_GONE -> AppError.playlistGone(code)
        FailureClass.NO_CAPABILITY -> AppError.capability(code)
        FailureClass.STORAGE -> AppError.storage(code)
        FailureClass.PERMISSION -> AppError.permission(code)
        FailureClass.CANCELLED -> AppError.cancelled(code)
        FailureClass.UNKNOWN -> AppError.unknown(code)
    }

fun failoverInput(
    channelId: Long = 1L,
    attempt: Int = 1,
    activeStreamId: Long? = 10L,
    candidates: List<Stream> = listOf(stream(10), stream(11)),
    failure: AppError? = null,
    stall: StallSignal? = null,
    health: Map<Long, StreamHealth> = emptyMap(),
    nowMs: Long = 0L,
    limits: FailoverLimits = FailoverLimits(),
    startupFailure: Boolean = false,
): FailoverInput = FailoverInput(
    channelId = channelId,
    attempt = attempt,
    activeStreamId = activeStreamId,
    candidates = candidates,
    failure = failure,
    stall = stall,
    health = health,
    nowMs = nowMs,
    limits = limits,
    startupFailure = startupFailure,
)
