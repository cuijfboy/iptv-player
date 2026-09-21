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

/**
 * One configured source (docs/02 §4.2, frozen shape). P2-4a only *produces* these from the built-in
 * catalogue; P2-6 (subscription management UI) is what lets the user add, edit and disable them, and
 * the `SourceRepository` port (§4.3) is what stores them.
 */
data class SourceConfig(
    val id: String,
    val providerId: String,
    val label: String,
    val url: String,
    val kind: SourceKind,
    val enabled: Boolean,
    val builtIn: Boolean,
)
