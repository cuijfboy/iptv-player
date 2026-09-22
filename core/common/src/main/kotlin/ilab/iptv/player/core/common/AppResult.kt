package ilab.iptv.player.core.common

/**
 * Result carried across module boundaries (docs/02 §4.1, interface v1).
 * Exceptions never cross a boundary: failures travel as [AppError].
 * `CancellationException` is the one exception — rethrow it, never wrap it.
 */
sealed interface AppResult<out T> {
    data class Ok<T>(val value: T) : AppResult<T>
    data class Err(val error: AppError) : AppResult<Nothing>
}

fun <T> AppResult<T>.valueOrNull(): T? = (this as? AppResult.Ok)?.value

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Ok -> AppResult.Ok(transform(value))
    is AppResult.Err -> this
}

inline fun <T> AppResult<T>.onErr(action: (AppError) -> Unit): AppResult<T> {
    if (this is AppResult.Err) action(error)
    return this
}

/** The single failure taxonomy; this is what the failover policy switches on (docs/02 §4.6). */
enum class FailureClass {
    HTTP_CLIENT,
    HTTP_SERVER,
    TIMEOUT,
    NET_UNREACHABLE,
    TLS,
    PARSE,
    DECODE_UNSUPPORTED,
    DECODE_CORRUPT,
    EMPTY_MEDIA,
    PLAYLIST_GONE,
    STORAGE,
    PERMISSION,
    NO_CAPABILITY,
    CANCELLED,
    UNKNOWN,
}

/**
 * Where a failure came from (docs/05 66 环境闸门治理).
 *
 * The same wire symptom can mean two very different things: the source answered for itself and
 * refused us ([SOURCE]), or a gateway / WAF / CDN / proxy in front of the source refused the
 * request before it ever reached the source ([ENV_GATED]). Only the first is a statement about
 * source quality; the second is this network's gate and must not be booked against the source.
 */
enum class FailureOrigin {
    /** The source (or its own CDN) answered: the failure is about this source's quality. */
    SOURCE,

    /** An environment gate in front of the source refused the request: not a source fault. */
    ENV_GATED,
}

/**
 * The non-standard HTTP statuses a gateway / WAF / CDN gate returns instead of the source's own
 * answer (docs/05 §50 / §31): `418` from CloudWAF, `605` from the miguvideo CDN, plus the
 * `451`/`511` family seen while scanning the fake-IP range.
 *
 * These are deliberately **not** the plain 403/404/410 "this source is gone" codes: those keep
 * their existing §4.6 meaning (`HTTP_CLIENT` → `SwitchTo(next)` + permanent demotion).
 */
object EnvGate {
    val HTTP_STATUSES: Set<Int> = setOf(418, 451, 511, 605)

    fun isGated(status: Int?): Boolean = status != null && status in HTTP_STATUSES
}

/**
 * A structured, redacted failure. [code] must come from `EventCodes` (docs/03 §3.3).
 * Use the factories — business code must not pick a [FailureClass] by hand.
 */
data class AppError(
    val code: String,
    val failure: FailureClass,
    val retryable: Boolean,
    val httpStatus: Int? = null,
    val detail: String? = null,
    val cause: Throwable? = null,
    /**
     * Source fault vs environment gate (docs/05 66). Defaults to [FailureOrigin.SOURCE] so every
     * pre-existing construction site — including the factories below — keeps its old meaning.
     */
    val origin: FailureOrigin = FailureOrigin.SOURCE,
) {
    companion object {
        /**
         * An HTTP answer. A gateway/WAF/CDN status ([EnvGate]) is filed as [FailureOrigin.ENV_GATED]
         * and stays retryable (env_gated: 本轮切换/退避, 不永久降权 — docs/05 66); every other status
         * keeps the P0 split (4xx → `HTTP_CLIENT`, 5xx/408/429 → `HTTP_SERVER`).
         */
        fun http(status: Int, code: String, cause: Throwable? = null): AppError {
            val gated = EnvGate.isGated(status)
            return AppError(
                code = code,
                failure = if (status in 400..499) FailureClass.HTTP_CLIENT else FailureClass.HTTP_SERVER,
                retryable = status >= 500 || status == 408 || status == 429 || gated,
                httpStatus = status,
                cause = cause,
                origin = if (gated) FailureOrigin.ENV_GATED else FailureOrigin.SOURCE,
            )
        }

        fun timeout(code: String, cause: Throwable? = null): AppError =
            AppError(code, FailureClass.TIMEOUT, retryable = true, cause = cause)

        fun network(code: String, cause: Throwable? = null): AppError =
            AppError(code, FailureClass.NET_UNREACHABLE, retryable = true, cause = cause)

        fun parse(code: String, cause: Throwable? = null): AppError =
            AppError(code, FailureClass.PARSE, retryable = false, cause = cause)

        /** TLS handshake failure (docs/02 §4.6): switching source is the only move, retrying is not. */
        fun tls(code: String, cause: Throwable? = null): AppError =
            AppError(code, FailureClass.TLS, retryable = false, cause = cause)

        fun decode(code: String, unsupported: Boolean, cause: Throwable? = null): AppError = AppError(
            code = code,
            failure = if (unsupported) FailureClass.DECODE_UNSUPPORTED else FailureClass.DECODE_CORRUPT,
            retryable = !unsupported,
            cause = cause,
        )

        /** The stream answered but carried no media at all — an empty manifest / no segments (docs/02 §4.6). */
        fun emptyMedia(code: String, detail: String? = null, cause: Throwable? = null): AppError =
            AppError(code, FailureClass.EMPTY_MEDIA, retryable = false, detail = detail, cause = cause)

        /**
         * Every candidate of one channel is dead (docs/02 §4.6). The failover policy answers with
         * `ResolveFresh` (re-probe that single channel), not with a source switch.
         */
        fun playlistGone(code: String, detail: String? = null, cause: Throwable? = null): AppError =
            AppError(code, FailureClass.PLAYLIST_GONE, retryable = false, detail = detail, cause = cause)

        /**
         * Coroutine cancellation (docs/02 §4.6): not a failure. Callers must not emit
         * `PLAY_FAILOVER` for it; it is carried as [FailureClass.CANCELLED] only so it can travel
         * as an [AppResult.Err] where a `CancellationException` may not be thrown.
         */
        fun cancelled(code: String, cause: Throwable? = null): AppError =
            AppError(code, FailureClass.CANCELLED, retryable = false, cause = cause)

        fun storage(code: String, cause: Throwable? = null): AppError =
            AppError(code, FailureClass.STORAGE, retryable = true, cause = cause)

        fun permission(code: String, cause: Throwable? = null): AppError =
            AppError(code, FailureClass.PERMISSION, retryable = false, cause = cause)

        fun capability(code: String, detail: String? = null): AppError =
            AppError(code, FailureClass.NO_CAPABILITY, retryable = false, detail = detail)

        fun unknown(code: String, cause: Throwable? = null): AppError =
            AppError(code, FailureClass.UNKNOWN, retryable = false, cause = cause)
    }
}
