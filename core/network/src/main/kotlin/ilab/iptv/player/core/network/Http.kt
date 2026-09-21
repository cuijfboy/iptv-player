package ilab.iptv.player.core.network

import ilab.iptv.player.core.common.AppResult

/**
 * HTTP verbs the refresh pipeline uses (docs/02 §6.1 Fetch/Shallow). HEAD is the cheap
 * reachability probe; GET is the playlist body and the shallow "first slice" fallback.
 */
enum class HttpMethod { GET, HEAD }

/**
 * One outbound request. Immutable and free of OkHttp types, so a unit test can describe a request
 * without a server and an in-memory [HttpFetcher] fake can answer it (docs/02 §4.0 F2: no
 * exception crosses a module boundary, the answer is an [AppResult]).
 *
 * [firstBytesOnly] asks for a bounded prefix (shallow edge-slice probe, docs/02 §6.1) and is also
 * honoured for GET; when it is set the transport may send a `Range` header and may return a body
 * shorter than [maxBytes].
 */
data class HttpRequest(
    val url: String,
    val method: HttpMethod = HttpMethod.GET,
    /** Per-source User-Agent; falls back to the pipeline default when null (docs/02 §6.1 Fetch). */
    val userAgent: String? = null,
    /** `http-referrer` collected off `#EXTVLCOPT` / `#EXTINF` (docs/02 §6.1). */
    val referrer: String? = null,
    val extraHeaders: Map<String, String> = emptyMap(),
    val timeoutMs: Long = 15_000,
    val maxBytes: Long = 8L * 1024 * 1024,
    val firstBytesOnly: Long? = null,
)

/**
 * A bounded response body. [truncated] is true when the body hit [HttpRequest.maxBytes] (or the
 * requested [HttpRequest.firstBytesOnly]); the caller decides whether a short body is a failure
 * (playlists need the whole thing, the shallow probe does not).
 */
class HttpResponse(
    val status: Int,
    val bytes: ByteArray,
    val contentType: String?,
    val elapsedMs: Long,
    val truncated: Boolean,
) {
    val isSuccess: Boolean get() = status in 200..299

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HttpResponse) return false
        return status == other.status &&
            bytes.contentEquals(other.bytes) &&
            contentType == other.contentType &&
            elapsedMs == other.elapsedMs &&
            truncated == other.truncated
    }

    override fun hashCode(): Int {
        var result = status
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + (contentType?.hashCode() ?: 0)
        result = 31 * result + elapsedMs.hashCode()
        result = 31 * result + truncated.hashCode()
        return result
    }

    override fun toString(): String =
        "HttpResponse(status=$status, bytes=${bytes.size}, contentType=$contentType, " +
            "elapsedMs=$elapsedMs, truncated=$truncated)"
}

/**
 * The one seam between the pipeline and the network (docs/02 §3.1 `:core:network`). Implementations
 * must never throw for an I/O failure — they answer with [AppResult.Err] — and must rethrow
 * [kotlin.coroutines.cancellation.CancellationException] untouched (docs/02 §4.0 F2).
 */
interface HttpFetcher {
    suspend fun fetch(request: HttpRequest): AppResult<HttpResponse>
}

/**
 * Bounded exponential backoff (docs/02 §6.1 Fetch: "重试退避"). [maxAttempts] counts the first
 * try, so `maxAttempts = 2` means one retry. Backoff is capped at [maxBackoffMs] so a dead source
 * cannot stretch a stage.
 */
data class HttpRetryPolicy(
    val maxAttempts: Int = 2,
    val initialBackoffMs: Long = 300,
    val backoffFactor: Double = 2.0,
    val maxBackoffMs: Long = 2_000,
) {
    /** Delay before the retry that follows a failure of 1-based [attempt]; 0 when there is no next try. */
    fun backoffMs(attempt: Int): Long {
        if (attempt >= maxAttempts) return 0
        var delay = initialBackoffMs.toDouble()
        repeat(attempt - 1) { delay *= backoffFactor }
        return delay.toLong().coerceAtMost(maxBackoffMs)
    }
}

/** The UA a playlist fetch uses when the source did not carry one (matches the Python baseline). */
const val DEFAULT_USER_AGENT: String =
    "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
