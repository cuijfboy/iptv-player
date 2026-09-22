package ilab.iptv.player.core.network

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.common.AppResult
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.FailureClass
import ilab.iptv.player.core.common.FailureOrigin
import java.net.UnknownHostException
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

/**
 * The transport is exercised through a short-circuiting [Interceptor] (no socket, no network), so
 * the retry loop, the byte cap and the failure mapping are all CI-runnable.
 */
class OkHttpFetcherTest {

    private val logger = RecordingLogger()
    private val url = "http://fake.local/list.m3u"

    private fun response(code: Int = 200, body: String = "#EXTM3U\n") = Response.Builder()
        .request(Request.Builder().url(url).build())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("msg")
        .body(body.toResponseBody())
        .build()

    /** A client whose one interceptor answers from [handler]; [handler] receives the attempt number. */
    private fun client(handler: (attempt: Int) -> Response): OkHttpClient {
        var count = 0
        return OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { _ ->
                    count++
                    handler(count)
                },
            )
            .build()
    }

    private fun fetch(client: OkHttpClient, request: HttpRequest = HttpRequest(url = url)) = runBlocking {
        OkHttpFetcher(client, logger, HttpRetryPolicy(initialBackoffMs = 0), sleep = { _ -> }).fetch(request)
    }

    // ---------------------------------------------------------------- streaming (P2-7)

    private fun open(
        client: OkHttpClient,
        request: HttpRequest = HttpRequest(url = url),
        attempts: Int = 2,
    ) = runBlocking {
        OkHttpFetcher(
            client,
            logger,
            HttpRetryPolicy(maxAttempts = attempts, initialBackoffMs = 0),
            sleep = { _ -> },
        ).open(request)
    }

    @Test
    fun `open hands back a live body instead of a byte array`() {
        val result = open(client { response(body = "<tv><programme/></tv>") })

        assertThat(result).isInstanceOf(AppResult.Ok::class.java)
        val body = (result as AppResult.Ok).value
        assertThat(body.status).isEqualTo(200)
        assertThat(body.stream.reader().readText()).isEqualTo("<tv><programme/></tv>")
        body.stream.close()
        assertThat(logger.count(EventCodes.NET_REQ_OK)).isEqualTo(1)
    }

    @Test
    fun `open retries only the open, and stops early on a non-retryable status`() {
        var attempts = 0
        val result = open(
            client {
                attempts++
                response(code = 404)
            },
            // A 404 is not retryable, so the second attempt must never happen.
            attempts = 3,
        )

        assertThat((result as AppResult.Err).error.httpStatus).isEqualTo(404)
        assertThat(attempts).isEqualTo(1)
        assertThat(logger.count(EventCodes.NET_REQ_FAIL)).isEqualTo(1)
    }

    @Test
    fun `open maps an unusable scheme to an error without touching the transport`() {
        val result = open(client { response() }, request = HttpRequest(url = "udp://239.0.0.1:1234"))
        assertThat(result).isInstanceOf(AppResult.Err::class.java)
        assertThat(logger.count(EventCodes.NET_REQ_FAIL)).isEqualTo(1)
    }

    @Test
    fun `success returns the body and logs NET_REQ_OK`() {
        val result = fetch(client { response() })
        assertThat(result).isInstanceOf(AppResult.Ok::class.java)
        val value = (result as AppResult.Ok).value
        assertThat(value.status).isEqualTo(200)
        assertThat(String(value.bytes)).contains("#EXTM3U")
        assertThat(logger.count(EventCodes.NET_REQ_OK)).isEqualTo(1)
        assertThat(logger.count(EventCodes.NET_REQ_FAIL)).isEqualTo(0)
    }

    @Test
    fun `a 5xx is retried and a later 200 wins`() {
        val result = fetch(client { attempt -> if (attempt == 1) response(503, "boom") else response() })
        assertThat(result).isInstanceOf(AppResult.Ok::class.java)
        assertThat(logger.count(EventCodes.NET_REQ_FAIL)).isEqualTo(1)
        assertThat(logger.count(EventCodes.NET_REQ_OK)).isEqualTo(1)
    }

    @Test
    fun `a 404 is not retried`() {
        var attempts = 0
        val result = fetch(client { attempts++; response(404, "nope") })
        assertThat(result).isInstanceOf(AppResult.Err::class.java)
        val error = (result as AppResult.Err).error
        assertThat(error.failure).isEqualTo(FailureClass.HTTP_CLIENT)
        assertThat(error.httpStatus).isEqualTo(404)
        assertThat(attempts).isEqualTo(1)
    }

    // ---- 环境闸门 (docs/05 66): the transport keeps the origin flag on the AppError ------------

    @Test
    fun `a gateway status surfaces as an ENV_GATED, still-retryable error`() {
        // A local in-process HTTP stub (an OkHttp interceptor — no socket, no network).
        listOf(418, 451, 511, 605).forEach { code ->
            val error = (fetch(client { response(code, "") }) as AppResult.Err).error
            assertThat(error.httpStatus).isEqualTo(code)
            assertThat(error.origin).isEqualTo(FailureOrigin.ENV_GATED)
            assertThat(error.retryable).isTrue()
        }
    }

    @Test
    fun `a plain 403 refusal stays a SOURCE origin on the error`() {
        val error = (fetch(client { response(403, "") }) as AppResult.Err).error
        assertThat(error.origin).isEqualTo(FailureOrigin.SOURCE)
        assertThat(error.retryable).isFalse()
    }

    @Test
    fun `a socket timeout is retryable and maps to TIMEOUT`() {
        val client = OkHttpClient.Builder()
            .addInterceptor { throw java.net.SocketTimeoutException("slow") }
            .build()
        val error = (fetch(client) as AppResult.Err).error
        assertThat(error.failure).isEqualTo(FailureClass.TIMEOUT)
        assertThat(error.retryable).isTrue()
        assertThat(logger.count(EventCodes.NET_REQ_FAIL)).isEqualTo(2)
    }

    @Test
    fun `an unknown host maps to NET_UNREACHABLE`() {
        val client = OkHttpClient.Builder()
            .addInterceptor { throw UnknownHostException("host") }
            .build()
        val error = (fetch(client) as AppResult.Err).error
        assertThat(error.failure).isEqualTo(FailureClass.NET_UNREACHABLE)
    }

    @Test
    fun `the body is capped at maxBytes`() {
        val big = "x".repeat(100_000)
        val request = HttpRequest(url = url, maxBytes = 1_024)
        val response = (fetch(client { response(body = big) }, request) as AppResult.Ok).value
        assertThat(response.bytes.size).isEqualTo(1_024)
    }

    @Test
    fun `a non-HTTP url is reported, not thrown`() {
        val result = fetch(client { response() }, HttpRequest(url = "rtsp://example/x"))
        assertThat(result).isInstanceOf(AppResult.Err::class.java)
        assertThat((result as AppResult.Err).error.retryable).isFalse()
    }

    @Test
    fun `HttpRetryPolicy backs off exponentially and caps`() {
        val policy = HttpRetryPolicy(maxAttempts = 4, initialBackoffMs = 300, backoffFactor = 2.0, maxBackoffMs = 1_000)
        assertThat(policy.backoffMs(1)).isEqualTo(300)
        assertThat(policy.backoffMs(2)).isEqualTo(600)
        assertThat(policy.backoffMs(3)).isEqualTo(1_000)
        assertThat(policy.backoffMs(4)).isEqualTo(0)
    }
}
