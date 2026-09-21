package ilab.iptv.player.core.source

import ilab.iptv.player.core.common.AppError
import ilab.iptv.player.core.common.AppResult
import ilab.iptv.player.core.common.Clock
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.LogEvent
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.network.HttpFetcher
import ilab.iptv.player.core.network.HttpRequest
import ilab.iptv.player.core.network.HttpResponse

/** A clock a test drives by hand (docs/02 §4.1 `Clock`). */
class FakeClock(private var now: Long = 1_000_000L) : Clock {
    override fun nowMs(): Long = now
    fun advance(ms: Long) {
        now += ms
    }
}

/** Captures event codes so a test can assert what the pipeline would log. */
class RecordingLogger : Logger {
    val codes = mutableListOf<String>()

    override fun log(event: LogEvent) {
        codes += event.code
    }

    override fun v(category: LogCategory, code: String, message: String, fields: Map<String, Any?>) {
        codes += code
    }

    override fun d(category: LogCategory, code: String, message: String, fields: Map<String, Any?>) {
        codes += code
    }

    override fun i(category: LogCategory, code: String, message: String, fields: Map<String, Any?>) {
        codes += code
    }

    override fun w(
        category: LogCategory,
        code: String,
        message: String,
        fields: Map<String, Any?>,
        error: Throwable?,
    ) {
        codes += code
    }

    override fun e(
        category: LogCategory,
        code: String,
        message: String,
        fields: Map<String, Any?>,
        error: Throwable?,
    ) {
        codes += code
    }

    override fun flush(timeoutMs: Long) = Unit

    fun count(code: String): Int = codes.count { it == code }
}

/** An in-memory [HttpFetcher]: records requests and answers from a test-supplied lambda. */
class FakeHttpFetcher(
    private val handler: (HttpRequest) -> AppResult<HttpResponse>,
) : HttpFetcher {
    val requests = mutableListOf<HttpRequest>()

    override suspend fun fetch(request: HttpRequest): AppResult<HttpResponse> {
        requests += request
        return handler(request)
    }

    companion object {
        fun ok(
            body: ByteArray,
            status: Int = 200,
            contentType: String? = "application/vnd.apple.mpegurl",
        ): (HttpRequest) -> AppResult<HttpResponse> = {
            AppResult.Ok(HttpResponse(status = status, bytes = body, contentType = contentType, elapsedMs = 12, truncated = false))
        }

        fun status(code: Int): (HttpRequest) -> AppResult<HttpResponse> = {
            AppResult.Ok(HttpResponse(status = code, bytes = ByteArray(0), contentType = null, elapsedMs = 5, truncated = false))
        }

        fun fail(error: AppError = AppError.timeout(EventCodes.NET_REQ_FAIL)): (HttpRequest) -> AppResult<HttpResponse> = {
            AppResult.Err(error)
        }
    }
}
