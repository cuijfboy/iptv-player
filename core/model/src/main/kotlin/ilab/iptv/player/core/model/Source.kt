package ilab.iptv.player.core.model

/**
 * How a source is delivered (docs/02 §4.2). The parser only ever produces [SourceKind.M3U] or
 * [SourceKind.TXT]; the other values describe providers that come later (`SourceProvider`, P2-4).
 */
enum class SourceKind { M3U, TXT, XTREAM, JSON, LOCAL_FILE }

/**
 * One raw playlist row, exactly as it came out of a source — the parser's output and the input to
 * normalization/scoring (docs/02 §4.2, frozen shape).
 *
 * This is deliberately *not* a `Channel`: a raw row has no database id, no group classification and
 * no health yet. Dedupe keys (`name_key` / `group_key` / `url_hash`) are derived from it by
 * `:core:source`'s `PlaylistNormalizer` (docs/02 §5.1).
 */
data class RawEntry(
    val name: String,
    val url: String,
    val tvgId: String? = null,
    val tvgName: String? = null,
    val groupTitle: String? = null,
    val logo: String? = null,
    val channelNo: Int? = null,
    val userAgent: String? = null,
    val referrer: String? = null,
    val sourceId: String,
)
