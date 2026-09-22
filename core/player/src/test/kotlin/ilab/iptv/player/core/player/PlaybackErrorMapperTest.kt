package ilab.iptv.player.core.player

import androidx.media3.common.PlaybackException
import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.FailureClass
import ilab.iptv.player.core.common.FailureOrigin
import org.junit.Test

/** docs/02 §4.6 现象 → FailureClass → retryable, pinned per media3 error code. */
class PlaybackErrorMapperTest {

    private val mapper = PlaybackErrorMapper()

    @Test
    fun `http status decides client vs server and retryability`() {
        val forbidden = mapper.classify(
            errorCode = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            errorCodeName = "BAD_HTTP",
            cause = httpCause(403),
        )
        assertThat(forbidden.failure).isEqualTo(FailureClass.HTTP_CLIENT)
        assertThat(forbidden.httpStatus).isEqualTo(403)
        assertThat(forbidden.retryable).isFalse()

        val throttled = mapper.classify(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, "BAD_HTTP", httpCause(429))
        assertThat(throttled.failure).isEqualTo(FailureClass.HTTP_SERVER)
        assertThat(throttled.retryable).isTrue()

        val serverError = mapper.classify(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, "BAD_HTTP", httpCause(503))
        assertThat(serverError.failure).isEqualTo(FailureClass.HTTP_SERVER)
        assertThat(serverError.retryable).isTrue()
        assertThat(serverError.httpStatus).isEqualTo(503)
    }

    @Test
    fun `bad http status without a readable code is treated as a dead source`() {
        val mapped = mapper.classify(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, "BAD_HTTP", cause = null)
        assertThat(mapped.failure).isEqualTo(FailureClass.HTTP_CLIENT)
        assertThat(mapped.retryable).isFalse()
        assertThat(mapped.httpStatus).isNull()
    }

    @Test
    fun `http status is read through the cause chain`() {
        val nested = RuntimeException("wrapper", httpCause(410))
        val mapped = mapper.classify(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, "BAD_HTTP", nested)
        assertThat(mapped.httpStatus).isEqualTo(410)
        assertThat(mapped.failure).isEqualTo(FailureClass.HTTP_CLIENT)
    }

    @Test
    fun `http status is read from a public field as well as a getter`() {
        // media3's InvalidResponseCodeException exposes `public final int responseCode`, not a getter.
        val fromField = mapper.classify(
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            "BAD_HTTP",
            RuntimeException("wrap", FakeHttpStatusFieldCause(404)),
        )
        assertThat(fromField.httpStatus).isEqualTo(404)
        assertThat(fromField.failure).isEqualTo(FailureClass.HTTP_CLIENT)
    }

    @Test
    fun `file not found is a 404 client error`() {
        val mapped = mapper.classify(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, "FILE_NOT_FOUND")
        assertThat(mapped.failure).isEqualTo(FailureClass.HTTP_CLIENT)
        assertThat(mapped.httpStatus).isEqualTo(404)
    }

    // ---- 环境闸门 (docs/05 66): 418/451/511/605 are a gate, not a source verdict ---------------

    @Test
    fun `gateway statuses are flagged ENV_GATED and stay retryable`() {
        listOf(418, 451, 511, 605).forEach { status ->
            val mapped = mapper.classify(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, "BAD_HTTP", httpCause(status))
            assertThat(mapped.httpStatus).isEqualTo(status)
            assertThat(mapped.origin).isEqualTo(FailureOrigin.ENV_GATED)
            assertThat(mapped.retryable).isTrue()
        }
    }

    @Test
    fun `a plain source refusal keeps SOURCE origin and is not retryable`() {
        // Negative control for the gate set: 403/404/410 are the source itself saying "gone", so
        // they keep the frozen HTTP_CLIENT meaning (and, upstream, the permanent demotion).
        listOf(403, 404, 410).forEach { status ->
            val mapped = mapper.classify(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, "BAD_HTTP", httpCause(status))
            assertThat(mapped.failure).isEqualTo(FailureClass.HTTP_CLIENT)
            assertThat(mapped.origin).isEqualTo(FailureOrigin.SOURCE)
            assertThat(mapped.retryable).isFalse()
        }
    }

    @Test
    fun `a 511 or 605 gate status is HTTP_SERVER in class but still an environment origin`() {
        listOf(511, 605).forEach { status ->
            val mapped = mapper.classify(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, "BAD_HTTP", httpCause(status))
            assertThat(mapped.failure).isEqualTo(FailureClass.HTTP_SERVER)
            assertThat(mapped.origin).isEqualTo(FailureOrigin.ENV_GATED)
        }
    }

    @Test
    fun `429 and 5xx are server errors even though AppError_http would call 429 a client error`() {
        // docs/02 §4.6 files 429 with the 5xx row; the P0 `AppError.http` factory files it with the
        // 4xx row. The mapper follows §4.6 and the divergence is reported (docs/05 §12.6).
        val throttled = mapper.classify(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, "BAD_HTTP", httpCause(429))
        assertThat(throttled.failure).isEqualTo(FailureClass.HTTP_SERVER)
        assertThat(throttled.retryable).isTrue()

        val requestTimeout = mapper.classify(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, "BAD_HTTP", httpCause(408))
        assertThat(requestTimeout.failure).isEqualTo(FailureClass.HTTP_SERVER)
        assertThat(requestTimeout.retryable).isTrue()
    }

    @Test
    fun `timeouts and live edge overrun are retryable`() {
        listOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_TIMEOUT,
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
        ).forEach { code ->
            val mapped = mapper.classify(code, "TIMEOUT")
            assertThat(mapped.failure).isEqualTo(FailureClass.TIMEOUT)
            assertThat(mapped.retryable).isTrue()
        }
    }

    @Test
    fun `network failures are unreachable and retryable`() {
        listOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        ).forEach { code ->
            val mapped = mapper.classify(code, "NET")
            assertThat(mapped.failure).isEqualTo(FailureClass.NET_UNREACHABLE)
            assertThat(mapped.retryable).isTrue()
        }
    }

    @Test
    fun `parsing failures are not retryable`() {
        listOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
        ).forEach { code ->
            val mapped = mapper.classify(code, "PARSE")
            assertThat(mapped.failure).isEqualTo(FailureClass.PARSE)
            assertThat(mapped.retryable).isFalse()
        }
    }

    @Test
    fun `decoder problems split unsupported from corrupt`() {
        listOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        ).forEach { code ->
            val mapped = mapper.classify(code, "DECODE")
            assertThat(mapped.failure).isEqualTo(FailureClass.DECODE_UNSUPPORTED)
            assertThat(mapped.retryable).isFalse()
        }

        listOf(
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
        ).forEach { code ->
            val mapped = mapper.classify(code, "DECODE_CORRUPT")
            assertThat(mapped.failure).isEqualTo(FailureClass.DECODE_CORRUPT)
            assertThat(mapped.retryable).isTrue()
        }
    }

    @Test
    fun `permission and empty media keep their own classes`() {
        val permission = mapper.classify(PlaybackException.ERROR_CODE_IO_NO_PERMISSION, "NO_PERMISSION")
        assertThat(permission.failure).isEqualTo(FailureClass.PERMISSION)
        assertThat(permission.retryable).isFalse()

        val empty = mapper.classify(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE, "EMPTY")
        assertThat(empty.failure).isEqualTo(FailureClass.EMPTY_MEDIA)
        assertThat(empty.retryable).isFalse()
    }

    @Test
    fun `unknown codes fall back to unknown`() {
        val mapped = mapper.classify(PlaybackException.ERROR_CODE_UNSPECIFIED, "UNSPECIFIED")
        assertThat(mapped.failure).isEqualTo(FailureClass.UNKNOWN)
        assertThat(mapped.retryable).isFalse()
        assertThat(mapped.detail).isEqualTo("UNSPECIFIED")
    }

    @Test
    fun `every failure carries a registered event code`() {
        // The guard that keeps business code from inventing event names (docs/03 §3.3).
        assertThat(EventCodes.isRegistered(mapper.classify(PlaybackException.ERROR_CODE_UNSPECIFIED, "X").code)).isTrue()
        assertThat(EventCodes.isRegistered(mapper.classify(PlaybackException.ERROR_CODE_TIMEOUT, "X").code)).isTrue()
    }

    @Test
    fun `map turns a real PlaybackException into the same classification`() {
        val exception = PlaybackException("boom", httpCause(404), PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        val mapped = mapper.map(exception)
        assertThat(mapped.failure).isEqualTo(FailureClass.HTTP_CLIENT)
        assertThat(mapped.httpStatus).isEqualTo(404)
    }

    private fun httpCause(status: Int): Throwable = FakeHttpStatusCause(status)
}

/** Stands in for media3's `InvalidResponseCodeException` without dragging in the datasource classes. */
class FakeHttpStatusCause(private val status: Int) : RuntimeException("HTTP $status") {
    @Suppress("unused")
    fun getResponseCode(): Int = status
}

/** The other shape media3 uses: a public int field, mirroring `InvalidResponseCodeException`. */
class FakeHttpStatusFieldCause(@JvmField val responseCode: Int) : RuntimeException("HTTP $responseCode")
