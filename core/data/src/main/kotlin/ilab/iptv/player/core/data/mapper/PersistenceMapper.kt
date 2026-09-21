package ilab.iptv.player.core.data.mapper

import ilab.iptv.player.core.database.dao.ChannelWithStreamRows
import ilab.iptv.player.core.database.entity.ChannelEntity
import ilab.iptv.player.core.database.entity.PlayHistoryEntity
import ilab.iptv.player.core.database.entity.ProgrammeEntity
import ilab.iptv.player.core.database.entity.StreamEntity
import ilab.iptv.player.core.domain.channel.ChannelGrouping
import ilab.iptv.player.core.model.Channel
import ilab.iptv.player.core.model.EpgMatchType
import ilab.iptv.player.core.model.Programme
import ilab.iptv.player.core.model.Quality
import ilab.iptv.player.core.model.Stream
import ilab.iptv.player.core.model.StreamOutcome
import ilab.iptv.player.core.source.normalize.Keys

/**
 * docs/02 §5.2's middle arrow, second half: `Room Entity <-> Domain Model`.
 *
 * The arrow is one-way-per-function and total: every column of §5.1 has exactly one domain field and
 * the reverse. Entities stay inside `:core:database` + `:core:data` (never in a feature), and the
 * domain never learns that `epg_match` is a `TEXT` column — the enums are stored by *name* so a
 * reordered enum cannot silently rewrite rows (ordinals would).
 *
 * Three deliberate conversions:
 * - **`Channel.group` is derived, not stored.** §5.1 stores `group_key` / `group_title`; the coarse
 *   five-value [ChannelGroup] is the classifier's output (`ChannelGrouping.classify`), so it is
 *   recomputed on read. Passing `groupTitle ?: groupKey` is exact because the classifier normalizes
 *   its input and `group_key` *is* the normalized title.
 * - **timestamps**: `created_at` / `updated_at` are filled on write ([toEntity] with `nowMs`), and the
 *   DAO keeps the original `created_at` when it updates an existing row.
 * - **unknown enum text** (a row written by a newer version, or a hand-edited database) falls back to
 *   `OTHER` / `NONE` / `UNKNOWN` instead of throwing: a channel list must never fail to open because
 *   one row has an unrecognized value.
 */
object PersistenceMapper {

    // ---- channel ----

    fun toEntity(channel: Channel, nowMs: Long): ChannelEntity = ChannelEntity(
        id = channel.id,
        name = channel.name,
        nameKey = channel.nameKey.ifEmpty { Keys.nameKey(channel.name) },
        tvgId = channel.tvgId,
        groupKey = channel.groupKey.ifEmpty { ChannelGrouping.groupKey(channel.groupTitle) },
        groupTitle = channel.groupTitle,
        logo = channel.logoUrl,
        channelNo = channel.channelNo,
        favorite = channel.favorite,
        hidden = channel.hidden,
        sortOrder = channel.sortOrder,
        epgChannelId = channel.epgChannelId,
        epgMatch = channel.epgMatch.name,
        createdAt = channel.createdAtMs.takeIf { it > 0 } ?: nowMs,
        updatedAt = channel.updatedAtMs.takeIf { it > 0 } ?: nowMs,
    )

    /** `streamCount` is not a §5.1 column; the caller passes the number of streams it read. */
    fun toDomain(entity: ChannelEntity, streamCount: Int = 0): Channel = Channel(
        id = entity.id,
        name = entity.name,
        nameKey = entity.nameKey,
        tvgId = entity.tvgId,
        group = ChannelGrouping.classify(entity.groupTitle ?: entity.groupKey),
        groupKey = entity.groupKey,
        groupTitle = entity.groupTitle,
        logoUrl = entity.logo,
        channelNo = entity.channelNo,
        favorite = entity.favorite,
        hidden = entity.hidden,
        sortOrder = entity.sortOrder,
        epgChannelId = entity.epgChannelId,
        epgMatch = epgMatch(entity.epgMatch),
        streamCount = streamCount,
        createdAtMs = entity.createdAt,
        updatedAtMs = entity.updatedAt,
    )

    fun toDomain(row: ChannelWithStreamRows): Pair<Channel, List<Stream>> {
        val streams = row.streams.map(::toDomain)
        return toDomain(row.channel, streams.size) to streams
    }

    private fun epgMatch(value: String): EpgMatchType =
        EpgMatchType.entries.firstOrNull { it.name == value } ?: EpgMatchType.NONE

    // ---- stream ----

    fun toEntity(stream: Stream): StreamEntity = StreamEntity(
        id = stream.id,
        channelId = stream.channelId,
        url = stream.url,
        // The parser already hashed it; a hand-built Stream (tests, fixtures) gets the same
        // normalized-URL SHA-256 the parser uses, so "blank hash" cannot collapse two streams into one.
        urlHash = stream.urlHash.ifEmpty { Keys.urlHash(stream.url) },
        userAgent = stream.userAgent,
        referrer = stream.referrer,
        sourceId = stream.sourceId,
        quality = stream.quality?.name,
        vcodec = stream.videoCodec,
        acodec = stream.audioCodec,
        width = stream.width,
        height = stream.height,
        score = stream.score,
        priority = stream.priority,
        lastOkAt = stream.lastOkAtMs,
        lastCheckAt = stream.lastCheckAtMs,
        failCount = stream.failCount,
        lastError = stream.lastError,
        disabled = stream.disabled,
    )

    fun toDomain(entity: StreamEntity): Stream = Stream(
        id = entity.id,
        channelId = entity.channelId,
        url = entity.url,
        urlHash = entity.urlHash,
        userAgent = entity.userAgent,
        referrer = entity.referrer,
        sourceId = entity.sourceId,
        quality = entity.quality?.let { name -> Quality.entries.firstOrNull { it.name == name } },
        videoCodec = entity.vcodec,
        audioCodec = entity.acodec,
        width = entity.width,
        height = entity.height,
        score = entity.score,
        priority = entity.priority,
        lastOkAtMs = entity.lastOkAt,
        lastCheckAtMs = entity.lastCheckAt,
        failCount = entity.failCount,
        lastError = entity.lastError,
        disabled = entity.disabled,
    )

    /**
     * One `recordOutcome` call = one `play_history` row. The columns of §5.1 line up field for field
     * with a [StreamOutcome]: `started_at` is when it happened, `start_cost_ms` the measured cost,
     * `result` the failure class name (`OK` on success) and `channel_id` the channel the stream
     * belongs to.
     */
    fun toHistory(stream: StreamEntity, outcome: StreamOutcome): PlayHistoryEntity = PlayHistoryEntity(
        id = 0,
        channelId = stream.channelId,
        streamId = outcome.streamId,
        startedAt = outcome.atMs,
        startCostMs = outcome.costMs,
        result = outcome.resultLabel(),
        failoverCount = null,
    )

    private fun StreamOutcome.resultLabel(): String =
        if (ok) "OK" else failure?.name ?: detail ?: "UNKNOWN"

    // ---- programme ----

    fun toDomain(entity: ProgrammeEntity): Programme = Programme(
        id = entity.id,
        epgChannelId = entity.epgChannelId,
        startMs = entity.startMs,
        stopMs = entity.stopMs,
        title = entity.title,
        desc = entity.desc,
        category = entity.category,
    )

    fun toEntity(programme: Programme): ProgrammeEntity = ProgrammeEntity(
        id = 0,
        epgChannelId = programme.epgChannelId,
        startMs = programme.startMs,
        stopMs = programme.stopMs,
        title = programme.title,
        desc = programme.desc,
        category = programme.category,
    )

    // `source` / `epg_source` / `metric` have no domain type yet (docs/02 §4.3 lists `SourceConfig` and
    // `Metric`, but nothing in `:core:model` implements them — that is P2-6 / P2-8). Their entities and
    // DAOs exist so those cards have a table to write to; this mapper stays silent about them rather
    // than inventing a domain shape the frozen port has not defined.
}
