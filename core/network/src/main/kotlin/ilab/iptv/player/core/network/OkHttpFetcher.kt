package ilab.iptv.player.core.network

import ilab.iptv.player.core.common.AppError
import ilab.iptv.player.core.common.AppResult
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.Logger
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import kotlin.coroutines.cancellation.CancellationException

/**
 * OkHttp transport for the refresh pipeline (docs/02 §3.1 `:core:network`).
 *
 * Responsibilities and only these: build the request (UA / Referer / Range), enforce the per-request
 * timeout and byte cap, map an I/O failure to a registered [EventCodes] code, and retry the
 * retryable ones with [HttpRetryPolicy] backoff. Decoding the body to text is deliberately **not**
 * here — that is `:core:source`'s `TextDecoder` (GB18030 fallback), so this module stays free of
 * charset policy.
 *
 * Every attempt logs `NET_REQ_OK` / `NET_REQ_FAIL` (docs/03 §3.3). A source that fails all attempts
 * answers with [AppResult.Err]; a [CancellationException] is rethrown so a cancelled refresh stops
 * immediately (docs/02 §4.5 C5).
 */
class OkHttpFetcher(
    private val client: OkHttpClient,
    private val logger: Logger,
    private val retryPolicy: HttpRetryPolicy = HttpRetryPolicy(),
    /** Injected so tests run the retry loop with no real waiting. */
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : HttpFetcher, StreamingHttpFetcher {

    override suspend fun fetch(request: HttpRequest): AppResult<HttpResponse> {
        // OkHttp only speaks http/https; a udp/rtsp/rtmp target is not a transport failure but a
        // request that can never be served here. Answer with an Err (docs/02 §4.0 F2: no exception
        // crosses the boundary) instead of letting Request.Builder throw.
        if (!isHttpUrl(request.url)) {
            val error = AppError.unknown(
                EventCodes.NET_REQ_FAIL,
                IllegalArgumentException("unsupported scheme: ${request.url.substringBefore("://")}"),
            )
            logFailure(request, attempt = 1, error = error)
            return AppResult.Err(error)
        }
        var lastError: AppError? = null
        var attempt = 0
        while (attempt < retryPolicy.maxAttempts) {
            attempt++
            val outcome = try {
                attemptOnce(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                AppResult.Err(mapIoFailure(e))
            }

            when (outcome) {
                is AppResult.Ok -> {
                    logRequest(request, attempt, outcome.value)
                    return outcome
                }
                is AppResult.Err -> {
                    lastError = outcome.error
                    logFailure(request, attempt, outcome.error)
                    if (!outcome.error.retryable) return outcome
                }
            }
            val backoff = retryPolicy.backoffMs(attempt)
            if (backoff > 0) sleep(backoff)
        }
        return AppResult.Err(lastError ?: AppError.unknown(EventCodes.NET_REQ_FAIL))
    }

    private suspend fun attemptOnce(request: HttpRequest): AppResult<HttpResponse> =
        suspendCancellableCoroutine { cont ->
            val call = client.newBuilder()
                .callTimeout(request.timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build()
                .newCall(buildRequest(request))
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (!cont.isActive) return
                        cont.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            response.use { cont.resume(readResponse(it, request)) }
                        } catch (e: IOException) {
                            if (cont.isActive) cont.resumeWithException(e)
                        } catch (e: Throwable) {
                            if (cont.isActive) cont.cancel(e)
                        }
                    }
                },
            )
        }

    // ---------------------------------------------------------------- streaming (P2-7)

    /**
     * Opens the body without buffering it (docs/02 §6.3). Retries only the *open* — a failure that
     * happens after the caller started reading cannot be replayed, because the caller has already
     * consumed part of the previous body.
     *
     * The response is deliberately **not** closed here: closing it closes the body, and the caller is
     * the one who reads it. Ownership transfers with [HttpBody.stream].
     */
    override suspend fun open(request: HttpRequest): AppResult<HttpBody> {
        if (!isHttpUrl(request.url)) {
            val error = AppError.unknown(
                EventCodes.NET_REQ_FAIL,
                IllegalArgumentException("unsupported scheme: ${request.url.substringBefore("://")}"),
            )
            logFailure(request, attempt = 1, error = error)
            return AppResult.Err(error)
        }
        var lastError: AppError? = null
        var attempt = 0
        while (attempt < retryPolicy.maxAttempts) {
            attempt++
            val outcome = try {
                openOnce(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                AppResult.Err(mapIoFailure(e))
            }
            when (outcome) {
                is AppResult.Ok -> {
                    logger.d(
                        LogCategory.NET,
                        EventCodes.NET_REQ_OK,
                        "http open",
                        mapOf(
                            "url" to request.url,
                            "status" to outcome.value.status,
                            "bytes" to outcome.value.contentLength,
                            "attempt" to attempt,
                        ),
                    )
                    return outcome
                }
                is AppResult.Err -> {
                    lastError = outcome.error
                    logFailure(request, attempt, outcome.error)
                    if (!outcome.error.retryable) return outcome
                }
            }
            val backoff = retryPolicy.backoffMs(attempt)
            if (backoff > 0) sleep(backoff)
        }
        return AppResult.Err(lastError ?: AppError.unknown(EventCodes.NET_REQ_FAIL))
    }

    private suspend fun openOnce(request: HttpRequest): AppResult<HttpBody> =
        suspendCancellableCoroutine { cont ->
            val call = client.newBuilder()
                .callTimeout(request.timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build()
                // `firstBytesOnly` is a byte *cap* for the buffered path; a streaming caller wants the
                // whole body (it applies its own window filter), so the Range header is not sent.
                .newCall(buildRequest(request.copy(firstBytesOnly = null)))
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (!cont.isActive) return
                        cont.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        // Any non-2xx answer is a normal error: close it and map it, never hand a body
                        // to the caller that it would have to notice was an error page.
                        if (response.code !in 200..299) {
                            val error = AppError.http(response.code, EventCodes.NET_REQ_FAIL)
                            response.close()
                            if (cont.isActive) cont.resume(AppResult.Err(error))
                            return
                        }
                        val body: ResponseBody? = response.body
                        if (body == null) {
                            response.close()
                            if (cont.isActive) {
                                cont.resume(
                                    AppResult.Err(
                                        AppError.emptyMedia(EventCodes.NET_REQ_FAIL, "empty body"),
                                    ),
                                )
                            }
                            return
                        }
                        val stream: InputStream = body.byteStream()
                        if (cont.isActive) {
                            cont.resume(
                                AppResult.Ok(
                                    HttpBody(
                                        status = response.code,
                                        contentType = body.contentType()?.toString(),
                                        contentLength = body.contentLength().takeIf { it >= 0 },
                                        stream = stream,
                                    ),
                                ),
                            )
                        } else {
                            // Cancelled between the answer and the handover: nothing owns the stream.
                            runCatching { stream.close() }
                            response.close()
                        }
                    }
                },
            )
        }

    private fun buildRequest(request: HttpRequest): Request {
        val builder = Request.Builder()
            .url(request.url)
            .header("User-Agent", request.userAgent ?: DEFAULT_USER_AGENT)
        request.referrer?.let { builder.header("Referer", it) }
        request.extraHeaders.forEach { (k, v) -> builder.header(k, v) }
        request.firstBytesOnly?.let { builder.header("Range", "bytes=0-${it - 1}") }
        builder.method(request.method.name, null)
        return builder.build()
    }

    private fun readResponse(response: Response, request: HttpRequest): AppResult<HttpResponse> {
        val status = response.code
        val started = System.nanoTime()
        val body: ResponseBody? = response.body
        val bytes: ByteArray = if (request.method == HttpMethod.HEAD || body == null) {
            ByteArray(0)
        } else {
            readBounded(body, request.maxBytes)
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        if (status !in 200..299) {
            return AppResult.Err(AppError.http(status, EventCodes.NET_REQ_FAIL))
        }
        val limit = request.firstBytesOnly
        val truncated = limit != null && bytes.size.toLong() >= limit
        return AppResult.Ok(
            HttpResponse(
                status = status,
                bytes = bytes,
                contentType = body?.contentType()?.toString(),
                elapsedMs = elapsedMs,
                truncated = truncated,
            ),
        )
    }

    /** Reads at most [maxBytes] so a hostile/oversized body cannot exhaust the heap. */
    private fun readBounded(body: ResponseBody, maxBytes: Long): ByteArray {
        val stream = body.byteStream()
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (total < maxBytes) {
            val read = stream.read(buffer, 0, minOf(buffer.size.toLong(), maxBytes - total).toInt())
            if (read < 0) break
            out.write(buffer, 0, read)
            total += read
        }
        return out.toByteArray()
    }

    private fun mapIoFailure(e: IOException): AppError = when (e) {
        is SocketTimeoutException -> AppError.timeout(EventCodes.NET_REQ_FAIL, e)
        is InterruptedIOException -> AppError.timeout(EventCodes.NET_REQ_FAIL, e)
        is UnknownHostException -> AppError.network(EventCodes.NET_REQ_FAIL, e)
        is ConnectException -> AppError.network(EventCodes.NET_REQ_FAIL, e)
        is SSLException -> AppError.tls(EventCodes.NET_REQ_FAIL, e)
        else -> AppError.network(EventCodes.NET_REQ_FAIL, e)
    }

    private fun isHttpUrl(url: String): Boolean {
        val scheme = url.substringBefore("://", missingDelimiterValue = "").lowercase()
        return scheme == "http" || scheme == "https"
    }

    private fun logRequest(request: HttpRequest, attempt: Int, response: HttpResponse) {
        logger.d(
            LogCategory.NET,
            EventCodes.NET_REQ_OK,
            "http ok",
            mapOf(
                "url" to request.url,
                "status" to response.status,
                "bytes" to response.bytes.size,
                "attempt" to attempt,
                "costMs" to response.elapsedMs,
            ),
        )
    }

    private fun logFailure(request: HttpRequest, attempt: Int, error: AppError) {
        logger.w(
            LogCategory.NET,
            EventCodes.NET_REQ_FAIL,
            "http fail",
            mapOf(
                "url" to request.url,
                "status" to error.httpStatus,
                "failure" to error.failure.name,
                // 只加字段不加码 (docs/05 66): ENV_GATED = a gateway/WAF/CDN gate, not the source.
                "origin" to error.origin.name,
                "attempt" to attempt,
                "retryable" to error.retryable,
            ),
            error.cause,
        )
    }
}
