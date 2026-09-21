package ilab.iptv.player.core.domain.channel

import ilab.iptv.player.core.model.Channel
import ilab.iptv.player.core.model.ChannelGroup

/**
 * Grouping policy for the channel list (docs/02 §5.1 + §8.1), pure Kotlin so it is unit-testable
 * without Android.
 *
 * Two different things are called "group" in the docs and they must not be conflated:
 *
 * - **`group_key` / `group_title`** (docs/02 §5.1) are the *source's* grouping — a free-form string
 *   like `"地方/其他"`. The table stores `group_key TEXT NOT NULL` and its unique index is
 *   `UNIQUE(name_key, group_key)`; the comment on that index says same-named channels may coexist
 *   across groups, which only holds while every distinct group title keeps a distinct key.
 * - **[ChannelGroup]** (docs/02 §4.2) is a five-value *classification* used by the EPG coverage
 *   report (`EpgCoverage.byGroup`) and the coarse navigation order.
 *
 * So the key is derived from the title (docs/02 §5.1; `:core:source`'s `Keys.groupKey` does the same
 * for the parse stage) and the classification is derived from the title too. **Missing/blank title →
 * key `"other"`** (`channel.group_key` is `NOT NULL`, so there is no null case) and classification
 * [ChannelGroup.OTHER].
 *
 * The keyword table below is this package's rule, not a docs/02 §4.x contract: docs/02 never spells
 * out how a title maps to the enum. It is written out here so the mapping is data, not a chain of
 * `if`s buried in a mapper, and so a wrong classification is a one-line fix.
 */
object ChannelGrouping {

    /** Display order of the coarse classification in the list (docs/02 §8.1 tab order). */
    val displayOrder: List<ChannelGroup> = listOf(
        ChannelGroup.CCTV,
        ChannelGroup.SATELLITE,
        ChannelGroup.HK_MO_TW,
        ChannelGroup.LOCAL,
        ChannelGroup.OTHER,
    )

    /** Title keyword → classification, first match wins; scanned in this order. */
    private val classificationKeywords: List<Pair<ChannelGroup, List<String>>> = listOf(
        ChannelGroup.CCTV to listOf("央视", "中央", "cctv", "cgtn"),
        ChannelGroup.HK_MO_TW to listOf("港澳台", "香港", "澳门", "台湾", "凤凰", "翡翠", "明珠", "tvb", "hmt"),
        ChannelGroup.SATELLITE to listOf("卫视", "卫星", "satellite", "卡通", "少儿"),
        // "其他" is deliberately NOT a LOCAL keyword: the baseline carries both `地方/其他` (the
        // province/city bucket) and `其他频道`, and the first-match rule would otherwise swallow the
        // latter into LOCAL. Without it, `地方/其他` still lands in LOCAL through "地方".
        ChannelGroup.LOCAL to listOf("本地", "地方", "城市", "省内", "local"),
    )

    /**
     * The `group_key` for a raw group title (docs/02 §5.1). Normalization is the same shape as
     * `name_key`: full-width → half-width, whitespace collapsed, lower-cased. A blank or missing
     * title falls back to [ChannelGroup.OTHER]'s key (`"other"`).
     */
    fun groupKey(groupTitle: String?): String = normalizeKey(groupTitle).ifEmpty { ChannelGroup.OTHER.key }

    /** The coarse [ChannelGroup] a raw group title belongs to; blank/unknown → [ChannelGroup.OTHER]. */
    fun classify(groupTitle: String?): ChannelGroup {
        val needle = normalizeKey(groupTitle)
        if (needle.isEmpty()) return ChannelGroup.OTHER
        for ((group, keywords) in classificationKeywords) {
            if (keywords.any { needle.contains(it) }) return group
        }
        return ChannelGroup.OTHER
    }

    /** What the list header shows: the source's own title when it had one, else a readable fallback. */
    fun displayTitle(groupTitle: String?): String =
        groupTitle?.trim().takeUnless { it.isNullOrEmpty() } ?: fallbackTitle(classify(groupTitle))

    /** Readable header for a classification whose source had no title at all (docs/02 §8.1). */
    fun fallbackTitle(group: ChannelGroup): String = when (group) {
        ChannelGroup.CCTV -> "央视"
        ChannelGroup.SATELLITE -> "卫视"
        ChannelGroup.HK_MO_TW -> "港澳台"
        ChannelGroup.LOCAL -> "本地"
        ChannelGroup.OTHER -> "其他"
    }

    /** Rank of a classification in [displayOrder]; unknown values sort last. */
    fun rank(group: ChannelGroup): Int = displayOrder.indexOf(group).let { if (it < 0) displayOrder.size else it }

    /**
     * Cuts a channel list into list sections, one per `group_key`, ordered by [displayOrder] of the
     * classification and then by key. Channels inside a section keep their incoming order — the
     * caller has already sorted them ([ChannelSorter], [ChannelNumberAssigner]) so the list index
     * and the channel number agree.
     */
    fun sections(channels: List<Channel>): List<ChannelGroupSection> {
        val byKey = LinkedHashMap<String, MutableList<Channel>>()
        for (channel in channels) byKey.getOrPut(channel.groupKey) { ArrayList() } += channel
        return byKey.map { (key, members) ->
            val classification = members.first().group
            ChannelGroupSection(
                key = key,
                title = displayTitle(members.first().groupTitle),
                group = classification,
                channels = members,
            )
        }.sortedWith(compareBy({ rank(it.group) }, { it.key }))
    }

    /**
     * Normalization shared with `name_key`: full-width → half-width (ASCII range), whitespace runs
     * collapsed to one `_`, lower-cased. Mirrors `:core:source`'s `NameNormalizer.key` for the same
     * input; see §12 of the P1-2 record for why the rule currently exists in two modules.
     */
    fun normalizeKey(raw: String?): String {
        if (raw == null) return ""
        val out = StringBuilder(raw.length)
        var pendingSeparator = false
        for (ch in raw) {
            val half = toHalfWidth(ch)
            when {
                half.isWhitespace() -> if (out.isNotEmpty()) pendingSeparator = true
                else -> {
                    if (pendingSeparator) {
                        out.append('_')
                        pendingSeparator = false
                    }
                    out.append(half.lowercaseChar())
                }
            }
        }
        return out.toString()
    }

    private fun toHalfWidth(ch: Char): Char = when (ch) {
        '\u3000' -> ' '
        in '\uFF01'..'\uFF5E' -> (ch.code - 0xFEE0).toChar()
        else -> ch
    }
}

/**
 * One `group_key` of the list: the header text plus its channels, already in display order.
 */
data class ChannelGroupSection(
    val key: String,
    val title: String,
    val group: ChannelGroup,
    val channels: List<Channel>,
)
