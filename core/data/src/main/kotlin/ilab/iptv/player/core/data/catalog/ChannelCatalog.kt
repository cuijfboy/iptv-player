package ilab.iptv.player.core.data.catalog

import ilab.iptv.player.core.common.AppResult
import ilab.iptv.player.core.common.Clock
import ilab.iptv.player.core.data.mapper.MappedCatalog
import ilab.iptv.player.core.data.mapper.ChannelMapper
import ilab.iptv.player.core.data.store.CatalogSink
import ilab.iptv.player.core.model.ChannelGroup
import ilab.iptv.player.core.source.normalize.DedupeReport
import ilab.iptv.player.core.source.normalize.PlaylistNormalizer
import ilab.iptv.player.core.source.parser.ParseOutcome
import ilab.iptv.player.core.source.parser.PlaylistFormat
import ilab.iptv.player.core.source.parser.PlaylistParsers
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The P1-2 pipeline in one call: **parse → normalize/dedupe → map → store** (docs/02 §6.1's Fetch
 * stage replaced by "read a bundled fixture", because P1-2 is explicitly "先不落库" and has no
 * network source yet).
 *
 * Text in, [CatalogLoadReport] out — no Android type anywhere in the signature, so the unit tests
 * drive the real pipeline with fixture text instead of an emulator.
 *
 * **One write end.** Everything this class publishes goes through the injected [CatalogSink]; it no
 * longer knows about `ChannelStore` or Room. That is what lets the same pipeline feed the in-memory
 * store in tests and SQLite in production without a second code path (P2-1 × P2-6 收口).
 */
@Singleton
class ChannelCatalog @Inject constructor(
    private val sink: CatalogSink,
    private val clock: Clock,
) {

    /**
     * Parse → normalize/dedupe → map → store. Kept as the one-call form for the bootstrapper and
     * the P1-2 tests; the two halves are separate below so an importer can look at the result
     * *before* it replaces what the user is currently watching (P2-6).
     */
    suspend fun load(text: String, sourceId: String): CatalogLoadReport = commit(prepare(text, sourceId))

    /** Same pipeline from bytes, so charset fallback (docs/02 §6.1) is included. */
    suspend fun load(
        bytes: ByteArray,
        sourceId: String,
        charsetHint: String? = null,
    ): AppResult<CatalogLoadReport> =
        when (val prepared = prepare(bytes, sourceId, charsetHint)) {
            is AppResult.Err -> AppResult.Err(prepared.error)
            is AppResult.Ok -> AppResult.Ok(commit(prepared.value))
        }

    /** Parses, normalizes and maps [text] **without touching the store**. */
    fun prepare(text: String, sourceId: String): PreparedCatalog {
        val startedAt = System.nanoTime()
        val outcome: ParseOutcome = PlaylistParsers.parse(text, sourceId)
        return prepare(outcome, sourceId, startedAt)
    }

    /** [prepare] from raw bytes: the decoder runs first, so GB18030 lists still parse. */
    fun prepare(bytes: ByteArray, sourceId: String, charsetHint: String? = null): AppResult<PreparedCatalog> {
        val startedAt = System.nanoTime()
        return when (val parsed = PlaylistParsers.parse(bytes, sourceId, charsetHint)) {
            is AppResult.Err -> AppResult.Err(parsed.error)
            is AppResult.Ok -> AppResult.Ok(prepare(parsed.value, sourceId, startedAt))
        }
    }

    /**
     * Publishes a prepared catalog: the only place the [CatalogSink] is written. [nowMs] defaults to
     * the injected clock so callers cannot forget to stamp the rows; the in-memory sink ignores it.
     */
    suspend fun commit(
        prepared: PreparedCatalog,
        nowMs: Long = clock.nowMs(),
    ): CatalogLoadReport {
        sink.write(prepared.mapped, nowMs)
        return prepared.report
    }

    private fun prepare(outcome: ParseOutcome, sourceId: String, startedAt: Long): PreparedCatalog {
        val playlist = PlaylistNormalizer.normalize(outcome.entries)
        val mapped = ChannelMapper.toDomain(playlist)

        val groups = LinkedHashMap<ChannelGroup, Int>()
        for (channel in mapped.channels) groups[channel.group] = (groups[channel.group] ?: 0) + 1

        return PreparedCatalog(
            mapped = mapped,
            report = CatalogLoadReport(
                sourceId = sourceId,
                format = outcome.format,
                lines = outcome.lines,
                rawEntries = outcome.entries.size,
                skipped = outcome.skipped,
                streams = mapped.streams.size,
                channels = mapped.channels.size,
                streamDedupe = playlist.streamDedupe,
                channelMerge = playlist.channelDedupe,
                groups = groups,
                elapsedMs = (System.nanoTime() - startedAt) / 1_000_000,
            ),
        )
    }
}

/**
 * A fully parsed and mapped catalog that has **not** been published yet: the importer's chance to
 * reject an empty or unusable file without wiping the channel list the user is looking at.
 */
data class PreparedCatalog(val mapped: MappedCatalog, val report: CatalogLoadReport)

/**
 * What one load produced — the numbers the `SRC_PARSE_OK` / `SRC_DEDUPE` events carry (docs/03 §3.3)
 * and the numbers the P1-2 record reports.
 */
data class CatalogLoadReport(
    val sourceId: String,
    val format: PlaylistFormat,
    val lines: Int,
    /** Rows the parser turned into entries. */
    val rawEntries: Int,
    /** Rows the parser refused (docs/02 §6.1 Parse stage), not an error count. */
    val skipped: Int,
    val streams: Int,
    val channels: Int,
    val streamDedupe: DedupeReport,
    /** Raw entries collapsed to channels by `(name_key, group_key)`. */
    val channelMerge: DedupeReport,
    val groups: Map<ChannelGroup, Int>,
    val elapsedMs: Long,
)
