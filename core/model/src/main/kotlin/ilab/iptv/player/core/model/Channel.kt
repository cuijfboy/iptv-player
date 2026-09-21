package ilab.iptv.player.core.model

import ilab.iptv.player.core.common.FailureClass

enum class ChannelGroup(val key: String) {
    CCTV("cctv"),
    SATELLITE("sat"),
    HK_MO_TW("hmt"),
    LOCAL("local"),
    OTHER("other"),
}

enum class Quality { UHD_4K, FHD_1080, HD_720, SD, UNKNOWN }

enum class EpgMatchType { TVG_ID, NAME_EXACT, NAME_FUZZY, ALIAS, MANUAL, NONE }

data class Channel(
    val id: Long,
    val name: String,
    /**
     * `channel.name_key` (docs/02 §5.1): the normalized name that the `UNIQUE(name_key, group_key)`
     * index and the EPG matcher key on. "" when the producer had no name to normalize.
     */
    val nameKey: String = "",
    val tvgId: String?,
    val group: ChannelGroup,
    /** `channel.group_key` (docs/02 §5.1): the normalized *group title*, not the [ChannelGroup] enum. */
    val groupKey: String = ChannelGroup.OTHER.key,
    /** `channel.group_title` (docs/02 §5.1): the group as the source spelled it; null when ungrouped. */
    val groupTitle: String? = null,
    val logoUrl: String?,
    val channelNo: Int?,
    val favorite: Boolean,
    val hidden: Boolean,
    val sortOrder: Int,
    val epgChannelId: String?,
    val epgMatch: EpgMatchType,
    /** 0/1/2+; 1 means there is no backup stream to fail over to (docs/02 §4.6 R5). */
    val streamCount: Int,
    /** `channel.created_at` (docs/02 §5.1). 0 = not persisted yet (the P1-2 in-memory store). */
    val createdAtMs: Long = 0,
    /** `channel.updated_at` (docs/02 §5.1). 0 = not persisted yet (the P1-2 in-memory store). */
    val updatedAtMs: Long = 0,
)

data class Stream(
    val id: Long,
    val channelId: Long,
    val url: String,
    /** `stream.url_hash` (docs/02 §5.1): SHA-256 of the normalized URL, the `UNIQUE(channel_id, url_hash)` key. */
    val urlHash: String = "",
    val userAgent: String?,
    val referrer: String?,
    val sourceId: String,
    val quality: Quality?,
    val videoCodec: String?,
    val audioCodec: String?,
    val width: Int,
    val height: Int,
    val score: Int,
    val priority: Int,
    val lastOkAtMs: Long?,
    val lastCheckAtMs: Long?,
    val failCount: Int,
    val lastError: String?,
    val disabled: Boolean,
)

/**
 * One probe result for one stream (docs/02 §4.2). Written by the validation pipeline (P2-3) and read
 * by the scorer and the fail-over policy; P1-2 only needs the type to exist so the frozen
 * `StreamRepository.recordOutcome` port can be implemented and tested.
 */
data class StreamOutcome(
    val streamId: Long,
    val ok: Boolean,
    val atMs: Long,
    val costMs: Long? = null,
    val failure: FailureClass? = null,
    val detail: String? = null,
)

/** Rolling health of one stream (docs/02 §4.2), the input of the §4.3 fail-over policy. */
data class StreamHealth(
    val attempts: Int,
    val failures: Int,
    val lastOkAtMs: Long?,
    val consecutiveFails: Int,
)

/** streams are already ordered by the §4.3 selection rules. */
data class ChannelWithStreams(val channel: Channel, val streams: List<Stream>)

data class ChannelFilter(
    val group: ChannelGroup?,
    val favoritesOnly: Boolean,
    val includeHidden: Boolean,
    val query: String?,
)

data class Programme(
    val id: Long,
    val epgChannelId: String,
    val startMs: Long,
    val stopMs: Long,
    val title: String,
    val desc: String?,
    val category: String?,
)

data class NowNext(val now: Programme?, val next: Programme?)
