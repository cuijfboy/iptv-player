package ilab.iptv.player.core.source.provider

import ilab.iptv.player.core.common.Clock
import ilab.iptv.player.core.common.AppResult
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.model.RawEntry
import ilab.iptv.player.core.model.SourceKind
import ilab.iptv.player.core.network.HttpFetcher
import ilab.iptv.player.core.network.HttpMethod
import ilab.iptv.player.core.network.HttpRequest
import ilab.iptv.player.core.source.parser.PlaylistFormat
import ilab.iptv.player.core.source.parser.PlaylistParsers
import ilab.iptv.player.core.source.pipeline.PipelineLimits

/**
 * One public HTTP(S) aggregate list, fetched and parsed (docs/02 §6.1 Fetch/Parse).
 *
 * The 17 built-in sources are all instances of this class; only their [SourceDescriptor] differs, so
 * the *strategy* lives in one place and a new source is one catalogue row (§9 E1). Format is
 * auto-detected from the body (the Python baseline's rule) unless a descriptor pins it.
 *
 * Events emitted here: `SRC_FETCH_OK` / `SRC_FETCH_FAIL` (retrieval) and `SRC_PARSE_OK` /
 * `SRC_PARSE_FAIL` (parse) plus `NET_CHARSET_FALLBACK` when the body was not UTF-8 (docs/03 §3.3).
 * The transport's own `NET_REQ_OK` / `NET_REQ_FAIL` come from [HttpFetcher].
 */
class RemotePlaylistSourceProvider(
    private val descriptor: SourceDescriptor,
    private val fetcher: HttpFetcher,
    private val logger: Logger,
    private val limits: PipelineLimits = PipelineLimits(),
    /** `null` = detect from the body; pinning it is for a source whose dialect is known. */
    private val format: PlaylistFormat? = null,
) : SourceProvider {

    override val id: String get() = descriptor.id
    override val label: String get() = descriptor.label
    override val kind: SourceKind get() = descriptor.kind

    override suspend fun fetch(clock: Clock): AppResult<List<RawEntry>> {
        val startedAt = clock.nowMs()
        val request = HttpRequest(
            url = descriptor.url,
            method = HttpMethod.GET,
            timeoutMs = limits.fetchTimeoutMs,
            maxBytes = limits.maxBytes,
        )
        val response = fetcher.fetch(request)
        val costMs = clock.nowMs() - startedAt

        if (response is AppResult.Err) {
            logger.w(
                LogCategory.SOURCE,
                EventCodes.SRC_FETCH_FAIL,
                "source fetch failed",
                mapOf(
                    "provider" to descriptor.id,
                    "failure" to response.error.failure.name,
                    "status" to response.error.httpStatus,
                    "costMs" to costMs,
                ),
                response.error.cause,
            )
            return AppResult.Err(response.error)
        }
        val body = (response as AppResult.Ok).value
        logger.d(
            LogCategory.SOURCE,
            EventCodes.SRC_FETCH_OK,
            "source fetched",
            mapOf(
                "provider" to descriptor.id,
                "bytes" to body.bytes.size,
                "status" to body.status,
                "costMs" to costMs,
            ),
        )

        val parsed = PlaylistParsers.parse(body.bytes, descriptor.id, format = format)
        return when (parsed) {
            is AppResult.Err -> {
                logger.w(
                    LogCategory.SOURCE,
                    EventCodes.SRC_PARSE_FAIL,
                    "source parse failed",
                    mapOf("provider" to descriptor.id, "failure" to parsed.error.failure.name),
                    parsed.error.cause,
                )
                AppResult.Err(parsed.error)
            }
            is AppResult.Ok -> {
                val outcome = parsed.value
                if (outcome.charsetEventCode != null) {
                    logger.d(
                        LogCategory.NET,
                        EventCodes.NET_CHARSET_FALLBACK,
                        "charset fallback",
                        mapOf("provider" to descriptor.id, "charset" to outcome.charset, "lossy" to outcome.lossyDecode),
                    )
                }
                logger.d(
                    LogCategory.SOURCE,
                    EventCodes.SRC_PARSE_OK,
                    "source parsed",
                    mapOf(
                        "provider" to descriptor.id,
                        "format" to outcome.formatLabel,
                        "entries" to outcome.entries.size,
                        "skipped" to outcome.skipped,
                        "costMs" to clock.nowMs() - startedAt,
                    ),
                )
                AppResult.Ok(outcome.entries)
            }
        }
    }
}
