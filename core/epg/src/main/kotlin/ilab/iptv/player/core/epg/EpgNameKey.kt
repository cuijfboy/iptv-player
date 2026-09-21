package ilab.iptv.player.core.epg

import java.util.Locale

/**
 * The name key the EPG matcher compares on.
 *
 * **Why a copy exists at all.** docs/02 §3.2's matrix does not let `:core:epg` declare
 * `:core:source` (row `epg` allows common/log/model/network only), yet tier 2 of §6.3's match chain
 * compares an XMLTV `<display-name>` against `channel.name_key` — a value `:core:source`'s
 * `NameNormalizer` produced. The two implementations must therefore agree exactly, and that is not
 * left to good intentions: `:core:data` can see both modules, so
 * `NameKeyParityTest` in `:core:data` asserts `EpgNameKey.key(x) == Keys.nameKey(x)` over a corpus of
 * real-looking channel names. Production wiring passes `Keys::nameKey` in explicitly
 * (`EpgModule`), so this default is the fallback and the parity test is what pins it.
 *
 * The algorithm is `NameNormalizer.key`'s, character for character: full-width ASCII (`U+FF01`–
 * `U+FF5E`) and the ideographic space (`U+3000`) folded, whitespace runs collapsed, then all
 * whitespace removed and the result lower-cased. NFKC is deliberately **not** used — it rewrites
 * `①`→`1` and `㍿`→`株式会社`, which changes Chinese channel names nobody asked to change.
 */
object EpgNameKey {

    fun key(raw: String?): String {
        val display = display(raw)
        if (display.isEmpty()) return ""
        val out = StringBuilder(display.length)
        for (ch in display) if (!ch.isWhitespace()) out.append(ch)
        return out.toString().lowercase(Locale.ROOT)
    }

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

    private fun foldWidth(ch: Char): Char = when (ch) {
        '\u3000' -> ' '
        in '\uFF01'..'\uFF5E' -> (ch.code - 0xFEE0).toChar()
        else -> ch
    }
}
