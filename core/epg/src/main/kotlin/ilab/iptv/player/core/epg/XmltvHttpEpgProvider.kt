package ilab.iptv.player.core.epg

import ilab.iptv.player.core.common.AppResult
import ilab.iptv.player.core.common.Clock
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.network.HttpMethod
import ilab.iptv.player.core.network.HttpRequest
import ilab.iptv.player.core.network.StreamingHttpFetcher

/**
 * [EpgProvider] over HTTP (docs/02 §6.3 step 1). One instance per configured `epg_source` row.
 *
 * What it owns, and only this: build the request (timeout, UA), stream the body, inflate gzip, and
 * emit the two fetch event codes. **Retry lives in the transport**
 * (`OkHttpFetcher`'s `HttpRetryPolicy`, same policy as the playlist pipeline) so a 5xx is retried once
 * with backoff while a 404 is not — a per-provider retry loop on top would double the attempts of the
 * path that already has one.
 *
 * Failure behaviour is docs/02 §6.3's degradation rule: a failed fetch answers `Err` and the caller
 * keeps the previously stored programmes. It never throws, and it never half-succeeds.
 */
class XmltvHttpEpgProvider(
    override val id: String,
    override val label: String,
    private val url: String,
    private val fetcher: StreamingHttpFetcher,
    private val logger: Logger,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : EpgProvider {

    override suspend fun fetch(clock: Clock): AppResult<XmltvStream> {
        val startedAt = clock.nowMs()
        val request = HttpRequest(
            url = url,
            method = HttpMethod.GET,
            timeoutMs = timeoutMs,
        )
        val opened = fetcher.open(request)
        return when (opened) {
            is AppResult.Err -> {
                logger.w(
                    LogCategory.EPG,
                    EventCodes.EPG_FETCH_FAIL,
                    "epg fetch failed",
                    mapOf(
                        "provider" to id,
                        "url" to url,
                        "failure" to opened.error.failure.name,
                        "status" to opened.error.httpStatus,
                        "costMs" to (clock.nowMs() - startedAt),
                    ),
                    opened.error.cause,
                )
                opened
            }

            is AppResult.Ok -> {
                val response = opened.value
                val decoded = GzipSniffer.decode(response.stream)
                logger.i(
                    LogCategory.EPG,
                    EventCodes.EPG_FETCH_OK,
                    "epg fetched",
                    mapOf(
                        "provider" to id,
                        // `bytes` is what the server declared: the *compressed* size for a gzip body,
                        // which is why the parse report counts programmes rather than comparing sizes.
                        "bytes" to response.contentLength,
                        "gzip" to (decoded !== response.stream),
                        "status" to response.status,
                        "costMs" to (clock.nowMs() - startedAt),
                    ),
                )
                AppResult.Ok(
                    XmltvStream(sourceId = id, bytes = response.contentLength, body = decoded),
                )
            }
        }
    }

    companion object {
        /**
         * A guide is tens of megabytes and a slow mirror is normal; 20 s matches the client-level
         * `callTimeout` of `:core:network`'s OkHttpClient rather than inventing a looser one.
         */
        const val DEFAULT_TIMEOUT_MS: Long = 20_000
    }
}
