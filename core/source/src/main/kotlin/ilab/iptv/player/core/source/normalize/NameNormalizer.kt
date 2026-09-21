package ilab.iptv.player.core.source.normalize

import java.util.Locale

/**
 * Name/group normalization for the Parse → Normalize stage (docs/02 §6.1: "名称全角→半角/去空白").
 *
 * Two outputs, on purpose:
 *  - [display] keeps the name readable but makes width and spacing uniform (used for `channel.name`);
 *  - [key] is the dedupe/match key (`name_key`), where case and *all* whitespace are removed so
 *    `CCTV-1 综合`, `cctv-1综合` and `ＣＣＴＶ－１ 综合` land on one key.
 *
 * Only ASCII-range full-width forms (U+FF01–U+FF5E) and the ideographic space (U+3000) are folded.
 * NFKC would additionally rewrite things like `①`→`1` or `㍿`→`株式会社`, which changes Chinese
 * channel names in ways nobody asked for, so it is deliberately not used.
 */
object NameNormalizer {

    /** Readable form: full-width → half-width, whitespace runs collapsed to one space, trimmed. */
    fun display(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        val folded = StringBuilder(raw.length)
        var pendingSpace = false
        for (ch in raw) {
            val c = foldWidth(ch)
            if (c.isWhitespace()) {
                pendingSpace = folded.isNotEmpty()
            } else {
                if (pendingSpace) folded.append(' ')
                pendingSpace = false
                folded.append(c)
            }
        }
        return folded.toString()
    }

    /** Dedupe/match key: [display] then all whitespace removed and case folded. */
    fun key(raw: String?): String {
        val display = display(raw)
        if (display.isEmpty()) return ""
        val out = StringBuilder(display.length)
        for (ch in display) if (!ch.isWhitespace()) out.append(ch)
        return out.toString().lowercase(Locale.ROOT)
    }

    /**
     * Folds one character from a full-width form to its half-width equivalent.
     * U+FF01–U+FF5E is the ASCII printable range shifted by 0xFEE0; U+3000 is the ideographic space.
     */
    private fun foldWidth(ch: Char): Char = when (ch) {
        '\u3000' -> ' '
        in '\uFF01'..'\uFF5E' -> (ch.code - 0xFEE0).toChar()
        else -> ch
    }
}
