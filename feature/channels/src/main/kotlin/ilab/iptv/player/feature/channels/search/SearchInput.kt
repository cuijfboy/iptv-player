package ilab.iptv.player.feature.channels.search

/**
 * The typed query of the search screen, kept as its own tiny model so the "what may be typed" rules
 * are testable: the remote's number keys feed digits straight in (a channel number is the fastest
 * possible query), the on-screen keypad feeds letters, and 删除/清空/BACK empty it.
 */
class SearchInput(private val maxLength: Int = DEFAULT_MAX_LENGTH) {

    var text: String = ""
        private set

    val isEmpty: Boolean get() = text.isEmpty()

    /** True when [ch] can be part of a query: ASCII letter, digit, or a CJK character. */
    fun accepts(ch: Char): Boolean =
        ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || SearchText.isCjk(ch)

    /** Appends one character; false when it was rejected or the buffer is full. */
    fun append(ch: Char): Boolean {
        if (!accepts(ch) || text.length >= maxLength) return false
        text += ch.lowercaseChar()
        return true
    }

    /** Drops the last character; false when there was nothing to drop. */
    fun backspace(): Boolean {
        if (text.isEmpty()) return false
        text = text.dropLast(1)
        return true
    }

    /** The 清空 key. Returns whether anything was cleared. */
    fun clear(): Boolean {
        if (text.isEmpty()) return false
        text = ""
        return true
    }

    companion object {
        /** 20 characters is far longer than any channel or programme name on a TV. */
        const val DEFAULT_MAX_LENGTH = 20
    }
}
