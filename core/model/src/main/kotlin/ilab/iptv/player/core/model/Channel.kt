package ilab.iptv.player.core.model

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
    val tvgId: String?,
    val group: ChannelGroup,
    val logoUrl: String?,
    val channelNo: Int?,
    val favorite: Boolean,
    val hidden: Boolean,
    val sortOrder: Int,
    val epgChannelId: String?,
    val epgMatch: EpgMatchType,
    /** 0/1/2+; 1 means there is no backup stream to fail over to (docs/02 §4.6 R5). */
    val streamCount: Int,
)

data class Stream(
    val id: Long,
    val channelId: Long,
    val url: String,
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
