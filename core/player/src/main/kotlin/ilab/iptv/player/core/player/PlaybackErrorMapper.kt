package ilab.iptv.player.core.player

import androidx.media3.common.PlaybackException
import ilab.iptv.player.core.common.AppError
import ilab.iptv.player.core.common.EnvGate
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.FailureClass
import ilab.iptv.player.core.common.FailureOrigin

/**
 * Reads an HTTP status out of a media3 error's cause chain without importing the datasource classes.
 * The production implementation reflects on `getResponseCode()`; tests inject a fake.
 */
fun interface HttpStatusReader {
    fun read(cause: Throwable?): Int?

    companion object {
        /**
         * Walks up to 8 causes looking for an HTTP status. Both shapes matter:
         * - a public no-arg `getResponseCode(): Int` (the generic case), and
         * - a public int field named `responseCode`.
         *
         * The second one is not hypothetical: media3's `HttpDataSource.InvalidResponseCodeException`
         * exposes its status as a public FIELD, so a getter-only reader silently drops every real
         * 404/403/5xx into the "no status" fallback. Found on the device in P1-3 (docs/05 §12).
         */
        val Reflective: HttpStatusReader = HttpStatusReader { cause ->
            var current = cause
            var depth = 0
            while (current != null && depth < 8) {
                try {
                    val method = current.javaClass.getMethod("getResponseCode")
                    (method.invoke(current) as? Int)?.let { return@HttpStatusReader it }
                } catch (_: ReflectiveOperationException) {
                    // Not an HTTP error: keep walking.
                } catch (_: SecurityException) {
                    // Same — a reflection-hostile class is simply not a status carrier.
                }
                try {
                    (current.javaClass.getField("responseCode").get(current) as? Int)
                        ?.let { return@HttpStatusReader it }
                } catch (_: ReflectiveOperationException) {
                    // No such field on this cause either.
                } catch (_: SecurityException) {
                    // Same.
                }
                current = current.cause
                depth++
            }
            null
        }
    }
}

/**
 * Maps a media3 failure onto the frozen taxonomy of docs/02 §4.6 (现象 → `FailureClass` →
 * strategy). Every engine failure carries `EventCodes.PLAY_PREPARE_FAIL` as its code, so no
 * business code invents an event name (docs/03 §3.3).
 *
 * The mapping is intentionally pure: it takes an error code, a symbolic name and a cause, and it
 * returns an [AppError]. That makes the whole table unit-testable without a device.
 */
class PlaybackErrorMapper(private val httpStatus: HttpStatusReader = HttpStatusReader.Reflective) {

    fun map(error: PlaybackException): AppError =
        classify(error.errorCode, error.errorCodeName, error.cause)

    fun classify(errorCode: Int, errorCodeName: String, cause: Throwable? = null): AppError {
        val code = EventCodes.PLAY_PREPARE_FAIL
        return when (errorCode) {
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
                val status = httpStatus.read(cause)
                if (status == null) {
                    // No status to read: treat as "this source is dead" (§4.6 HTTP_CLIENT → SwitchTo).
                    AppError(code, FailureClass.HTTP_CLIENT, retryable = false, detail = errorCodeName, cause = cause)
                } else {
                    httpError(status, errorCodeName, cause)
                }
            }

            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                AppError.http(404, code, cause).copy(detail = errorCodeName)

            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_TIMEOUT,
            // Live-edge overrun: transient, so it takes the retry-then-switch action of §4.6 TIMEOUT.
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
            -> AppError.timeout(code, cause).copy(detail = errorCodeName)

            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            -> AppError.network(code, cause).copy(detail = errorCodeName)

            PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
                AppError.permission(code, cause).copy(detail = errorCodeName)

            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE ->
                AppError.parse(code, cause).copy(detail = errorCodeName)

            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE ->
                AppError.emptyMedia(code, errorCodeName, cause)

            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            -> AppError.parse(code, cause).copy(detail = errorCodeName)

            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            -> AppError.decode(code, unsupported = true, cause = cause).copy(detail = errorCodeName)

            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
            -> AppError.decode(code, unsupported = false, cause = cause).copy(detail = errorCodeName)

            // Everything else — DRM (not used: the product ships no DRM content), remote/cast
            // errors, runtime assertions, cleartext-policy refusals — has no §4.6 row. UNKNOWN is
            // the honest bucket: record the event and switch source.
            else -> AppError.unknown(code, cause).copy(detail = errorCodeName)
        }
    }

    /**
     * §4.6 splits HTTP by status: 403/404/410 are `HTTP_CLIENT` (dead stream, permanently
     * downweighted), while **429 and 5xx are `HTTP_SERVER`** (backoff → retry → switch).
     *
     * This deliberately does NOT call `AppError.http(...)`: the frozen P0 factory classifies by
     * `status in 400..499`, so it files 429 (and 408) under `HTTP_CLIENT`. The row that P1-6's
     * `FailoverPolicy` implements is §4.6's, so the classification is made here. Reported as an
     * open discrepancy between `AppError.http` and §4.6 (docs/05 §12.6).
     */
    private fun httpError(status: Int, errorCodeName: String, cause: Throwable?): AppError {
        val serverSide = status >= 500 || status == 429 || status == 408
        // 环境闸门 (docs/05 66): a gateway/WAF/CDN refusal (418/451/511/605) is not a source fault.
        // It is filed with FailureOrigin.ENV_GATED so the failover policy switches this round
        // without the permanent demotion a plain HTTP_CLIENT would earn, and stays retryable.
        val gated = EnvGate.isGated(status)
        return AppError(
            code = EventCodes.PLAY_PREPARE_FAIL,
            failure = if (serverSide) FailureClass.HTTP_SERVER else FailureClass.HTTP_CLIENT,
            retryable = serverSide || gated,
            httpStatus = status,
            detail = "HTTP $status ($errorCodeName)",
            cause = cause,
            origin = if (gated) FailureOrigin.ENV_GATED else FailureOrigin.SOURCE,
        )
    }
}
