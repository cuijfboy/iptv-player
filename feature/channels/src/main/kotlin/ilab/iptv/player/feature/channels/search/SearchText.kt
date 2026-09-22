package ilab.iptv.player.feature.channels.search

/**
 * Normalization and matching rules of the P3-2 search (频道名 + 节目名).
 *
 * ONE RULE, THREE CONSEQUENCES. Everything is folded into a "search key" of lowercase ASCII letters,
 * digits and CJK characters — spaces, hyphens, dots, brackets and full-width forms are dropped. So:
 *  - the search is case-insensitive ("cctv1" finds "CCTV1 综合");
 *  - full-width digits (a common artefact of M3U files) fold to half-width, so a name spelled
 *    "ＣＣＴＶ－１" and one spelled "CCTV1" produce the same key;
 *  - separators are *removed*, not turned into spaces, which makes "cctv1" a prefix of the key
 *    "cctv1综合" and "新闻" a substring of "cctv1新闻综合" — the two shapes a remote user can
 *    actually type.
 *
 * Pure and separate from the index so the matching contract (what counts as a match, and which
 * match beats which) is unit-tested instead of being discovered on a TV.
 */
object SearchText {

    /** Query/key folding. Never throws; characters outside the supported set are dropped. */
    fun normalize(raw: String): String {
        val out = StringBuilder(raw.length)
        raw.forEach { ch ->
            val folded = fold(ch)
            when {
                folded in 'a'..'z' || folded in '0'..'9' -> out.append(folded)
                isCjk(folded) -> out.append(folded)
                else -> Unit
            }
        }
        return out.toString()
    }

    /**
     * How well [key] answers [query]: 0 = the whole key, 1 = the key starts with it, 2 = it appears
     * inside the key, null = no match. The distinction is what makes "cctv1" put CCTV1 above
     * "CCTV13", and a channel name above a programme whose title merely contains the text.
     */
    fun quality(key: String, query: String): Int? = when {
        query.isEmpty() -> null
        key == query -> 0
        key.startsWith(query) -> 1
        key.contains(query) -> 2
        else -> null
    }

    /** True for the CJK ranges this product's channel and programme names actually use. */
    fun isCjk(ch: Char): Boolean =
        ch in '\u4E00'..'\u9FFF' || ch in '\u3400'..'\u4DBF' || ch in '\uF900'..'\uFAFF'

    /**
     * Half-width fold: full-width ASCII (U+FF01–U+FF5E) maps down by 0xFEE0, the ideographic space
     * (U+3000) is dropped, and everything else is lowercased.
     */
    private fun fold(ch: Char): Char = when {
        ch == '\u3000' -> ' '
        // The full-width fold lands on ASCII upper case as often as lower case ("Ｃ" → 'C'), so the
        // lowercase step still has to run afterwards.
        ch in '\uFF01'..'\uFF5E' -> (ch.code - 0xFEE0).toChar().lowercaseChar()
        else -> ch.lowercaseChar()
    }
}
