package ilab.iptv.player.core.data.catalog

import ilab.iptv.player.core.data.mapper.ChannelMapper
import ilab.iptv.player.core.data.store.ChannelStore
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
 */
@Singleton
class ChannelCatalog @Inject constructor(private val store: ChannelStore) {

    fun load(text: String, sourceId: String): CatalogLoadReport {
        val startedAt = System.nanoTime()
        val outcome: ParseOutcome = PlaylistParsers.parse(text, sourceId)
        val playlist = PlaylistNormalizer.normalize(outcome.entries)
        val mapped = ChannelMapper.toDomain(playlist)
        store.replaceAll(mapped.channels, mapped.streams)

        val groups = LinkedHashMap<ChannelGroup, Int>()
        for (channel in mapped.channels) groups[channel.group] = (groups[channel.group] ?: 0) + 1

        return CatalogLoadReport(
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
        )
    }
}

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
