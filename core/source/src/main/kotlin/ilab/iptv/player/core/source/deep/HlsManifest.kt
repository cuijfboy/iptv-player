package ilab.iptv.player.core.source.deep

/**
 * The little of HLS the deep probe needs (docs/02 §6.1 Deep: "能拉到分片").
 *
 * A live channel URL is usually a **master** playlist (`#EXT-X-STREAM-INF` variants, each with the
 * declared bandwidth/resolution/codecs) that points at one or more **media** playlists
 * (`#EXTINF` segments). The probe walks master → variant → segment and stops there: it fetches
 * bytes, it deliberately does **not** play (派单: "不做长时间播放").
 */

/** One `#EXT-X-STREAM-INF` variant. `width`/`height`/`codecs` are the playlist's *declared* values. */
data class HlsVariant(
    val uri: String,
    val bandwidth: Long?,
    val width: Int,
    val height: Int,
    val codecs: List<String>,
)

/**
 * A parsed playlist. [isMaster] is true when at least one variant was declared; [segments] is
 * populated for a media playlist (and one level down from a master); [mediaSequence] is
 * `#EXT-X-MEDIA-SEQUENCE`, which is what docs/02 §6.1's "媒体序号不前进" observation is read from.
 */
data class HlsManifest(
    val isMaster: Boolean,
    val variants: List<HlsVariant>,
    val segments: List<String>,
    val mediaSequence: Long?,
)
