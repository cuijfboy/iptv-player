package ilab.iptv.player.core.network

import ilab.iptv.player.core.common.AppResult
import java.io.InputStream

/**
 * A response whose body is **not** buffered (docs/02 §6.3: "XMLTV 可能几十 MB —— 必须流式").
 *
 * [HttpFetcher] answers with `HttpResponse`, whose body is a `ByteArray` capped at
 * `HttpRequest.maxBytes`; that is right for a playlist and wrong for an XMLTV feed, which is read
 * incrementally. This is the second, additive seam: the same request model, but the caller gets the
 * live body.
 *
 * **Ownership:** the caller owns [stream] and must close it. Closing releases the underlying
 * connection; an unclosed body leaks it. The transport never touches the stream after handing it
 * over, so a mid-stream failure surfaces as an `IOException` from `read`, not as an `AppResult`.
 */
class HttpBody(
    val status: Int,
    val contentType: String?,
    /** `Content-Length` when the server declared one; null for a chunked or gzipped-unknown body. */
    val contentLength: Long?,
    val stream: InputStream,
)

/**
 * The streaming half of the `:core:network` seam (docs/02 §3.1). Kept separate from [HttpFetcher] so
 * every existing caller and fake keeps working unchanged; only a large, incrementally-consumed body
 * needs it.
 *
 * Same contract as [HttpFetcher]: never throws for an I/O failure (answer [AppResult.Err]),
 * rethrow `CancellationException` untouched (docs/02 §4.0 F2).
 */
interface StreamingHttpFetcher {
    suspend fun open(request: HttpRequest): AppResult<HttpBody>
}
