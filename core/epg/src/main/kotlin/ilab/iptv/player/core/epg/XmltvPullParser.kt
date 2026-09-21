package ilab.iptv.player.core.epg

import java.io.Reader

/** One XMLTV `<channel>`: the id `programme[channel]` points at, plus its display names. */
data class XmltvChannel(val id: String, val displayNames: List<String>)

/** One XMLTV `<programme>` row, already converted to epoch milliseconds. */
data class XmltvProgramme(
    val epgChannelId: String,
    val startMs: Long,
    val stopMs: Long,
    val title: String,
    val desc: String?,
    val category: String?,
)

/**
 * What one pass over a guide produced. `malformed` is true when the document ended in the middle of a
 * tag — the rows already handed to the sink are still good, and the caller writes them (docs/02 §11:
 * a partial guide beats no guide).
 */
data class XmltvParseResult(
    val channels: Int,
    val programmes: Int,
    val skipped: Int,
    val malformed: Boolean,
)

/**
 * Streaming XMLTV reader (docs/02 §6.3 step 2, "必须流式（`XmlPullParser`/SAX 风格）").
 *
 * **Why a hand-written scanner instead of `XmlPullParser` or SAX.** Both would work on a device and
 * neither would work in `:core:epg`'s JVM unit tests, which is where the acceptance criterion lives:
 * `android.util.Xml` is an unmocked Android stub off-device, and pulling in a JVM XML stack would add
 * a dependency the matrix does not grant this module. The scanner reads the guide **once, forwards,
 * one character at a time through a fixed 64 KB buffer** and never needs `mark`/`reset`, so it is
 * streaming in the sense that matters: memory is bounded by the buffer plus the current element, not
 * by the file. That property is asserted directly in `XmltvPullParserStreamingTest`.
 *
 * What it deliberately does **not** do: entity expansion beyond the five predefined plus numeric
 * references (no DTD/`SYSTEM` handling, so a "billion laughs" document expands nothing), and no schema
 * validation. Unknown elements and unknown attributes are skipped, because an XMLTV feed that adds
 * `<credits>` or `<rating>` must not break the ones we do read.
 *
 * Malformed input is handled by *skipping*: an unterminated `<programme>` never reaches the sink, the
 * counts say so, and a document that ends mid-tag returns `malformed = true` instead of throwing.
 *
 * Both sinks are `suspend` on purpose: that is what makes the caller's batch write a real
 * back-pressure point. A refresh streams a guide straight into `INSERT OR REPLACE` batches of 500
 * (docs/02 §4.5 C4) and never holds more than one batch, so a 40 MB guide costs a batch-sized amount
 * of heap instead of a guide-sized one. A non-suspending sink could only be drained by blocking or by
 * buffering, and buffering is the thing this class exists to avoid.
 *
 * @param onChannel called for every complete `<channel>`; the index it builds is what the matcher
 *   uses, and XMLTV puts channels before programmes so it is complete by the time rows flow.
 * @param onProgramme called for every complete, valid `<programme>`.
 */
class XmltvPullParser(
    /** Per-element text cap. A hostile or broken guide cannot make one `<desc>` eat the heap. */
    private val maxTextLength: Int = MAX_TEXT_LENGTH,
    /** Tag cap, for the same reason: an unterminated tag must not buffer the whole document. */
    private val maxTagLength: Int = MAX_TAG_LENGTH,
) {

    suspend fun parse(
        input: Reader,
        onChannel: suspend (XmltvChannel) -> Unit = {},
        onProgramme: suspend (XmltvProgramme) -> Unit,
    ): XmltvParseResult {
        val scan = CharScan(input)
        var channels = 0
        var programmes = 0
        var skipped = 0
        var malformed = false

        var channel: ChannelBuilder? = null
        var programme: ProgrammeBuilder? = null
        var textTarget: String? = null
        val text = StringBuilder()

        fun flushText() {
            val target = textTarget ?: return
            // Entities are decoded here, once per element, rather than at each character: `&#x95fb;`
            // and `&nbsp;`-sized references survive being split across two read buffers this way.
            // CDATA content was pre-escaped when it was appended (see below) so it comes back literal.
            val value = unescape(text.toString())
            text.setLength(0)
            when (target) {
                TAG_DISPLAY_NAME -> channel?.displayNames?.add(value.trim())
                TAG_TITLE -> programme?.title = value.trim()
                TAG_DESC -> programme?.desc = value.trim().ifEmpty { null }
                TAG_CATEGORY -> programme?.category = value.trim().ifEmpty { null }
            }
        }

        var c = scan.next()
        while (c >= 0) {
            if (c != CHAR_LT) {
                // Text run: hand it to the open element, if it is one we keep text for.
                val sb = StringBuilder()
                while (c >= 0 && c != CHAR_LT) {
                    if (textTarget != null && sb.length < maxTextLength) sb.append(c.toChar())
                    c = scan.next()
                }
                if (textTarget != null) {
                    val room = maxTextLength - text.length
                    if (room > 0) text.append(sb, 0, minOf(room, sb.length))
                }
                continue
            }

            // A `<`: decide what it opens.
            val marker = scan.next()
            when {
                marker < 0 -> {
                    malformed = true
                }

                marker == CHAR_BANG -> {
                    // `<!--`, `<![CDATA[` or `<!DOCTYPE`.
                    when (scan.peekAfterBang()) {
                        BangKind.COMMENT -> if (!scan.skipPast("-->")) malformed = true
                        BangKind.CDATA -> {
                            val cdata = scan.readUntil("]]>")
                            if (cdata == null) {
                                malformed = true
                            } else if (textTarget != null) {
                                // CDATA is literal text, but the element is unescaped once at the end
                                // (see `flushText`), so `&` is escaped here to survive that pass
                                // unchanged — `&amp;` inside CDATA stays `&amp;` on screen.
                                val escaped = cdata.replace("&", "&amp;")
                                val room = maxTextLength - text.length
                                if (room > 0) text.append(escaped, 0, minOf(room, escaped.length))
                            }
                        }

                        BangKind.DECLARATION -> if (!scan.skipDoctype()) malformed = true
                        BangKind.UNKNOWN -> if (!scan.skipPast(">")) malformed = true
                    }
                }

                marker == CHAR_QUESTION -> {
                    // `<?xml ... ?>`: skip to `?>`. Many guides open with a one-line declaration.
                    if (!scan.skipPast("?>")) malformed = true
                }

                marker == CHAR_SLASH -> {
                    val body = scan.readTag(maxTagLength)
                    if (body == null) {
                        malformed = true
                    } else {
                        val name = body.trim()
                        if (textTarget != null && name == textTarget) {
                            flushText()
                            textTarget = null
                        }
                        when (name) {
                            TAG_CHANNEL -> {
                                channel?.let { built ->
                                    if (built.id.isNotBlank()) {
                                        onChannel(XmltvChannel(built.id, built.displayNames.toList()))
                                        channels++
                                    }
                                }
                                channel = null
                            }

                            TAG_PROGRAMME -> {
                                val built = programme?.build()
                                if (built != null) {
                                    onProgramme(built)
                                    programmes++
                                } else if (programme != null) {
                                    skipped++
                                }
                                programme = null
                            }
                        }
                    }
                }

                else -> {
                    val body = scan.readTag(maxTagLength, first = marker.toChar())
                    if (body == null) {
                        malformed = true
                    } else {
                        val parsed = TagBody.parse(body)
                        if (parsed.name == TAG_CHANNEL) {
                            channel = ChannelBuilder(parsed.attr("id").orEmpty())
                            textTarget = null
                        } else if (parsed.name == TAG_PROGRAMME) {
                            programme = ProgrammeBuilder(
                                epgChannelId = parsed.attr("channel").orEmpty(),
                                startMs = XmltvTime.parse(parsed.attr("start")),
                                stopMs = XmltvTime.parse(parsed.attr("stop")),
                            )
                            textTarget = null
                        } else if (isTextElement(parsed.name) && textTarget == null) {
                            textTarget = parsed.name
                            text.setLength(0)
                            if (parsed.selfClosing) {
                                flushText()
                                textTarget = null
                            }
                        }
                    }
                }
            }
            c = scan.next()
        }

        // A document that stops inside an element is malformed, and its half-read elements never emit.
        if (channel != null || programme != null) malformed = true
        return XmltvParseResult(
            channels = channels,
            programmes = programmes,
            skipped = skipped,
            malformed = malformed,
        )
    }

    private fun isTextElement(name: String): Boolean = when (name) {
        TAG_DISPLAY_NAME, TAG_TITLE, TAG_DESC, TAG_CATEGORY -> true
        else -> false
    }

    private class ChannelBuilder(val id: String) {
        val displayNames = mutableListOf<String>()
    }

    private class ProgrammeBuilder(
        val epgChannelId: String,
        val startMs: Long?,
        val stopMs: Long?,
    ) {
        var title: String = ""
        var desc: String? = null
        var category: String? = null

        /**
         * A programme is written only when it has a channel, a start and a title — the three things
         * every read path needs (`now`/`next` filter on `epg_channel_id`, order by `start_ms`, and the
         * info bar has nothing to show without a title). A missing `stop` gets
         * [XmltvTime.DEFAULT_DURATION_MS] instead of being dropped.
         */
        fun build(): XmltvProgramme? {
            val channelId = epgChannelId.trim()
            val start = startMs ?: return null
            if (channelId.isEmpty() || title.isEmpty()) return null
            val stop = stopMs?.takeIf { it > start } ?: (start + XmltvTime.DEFAULT_DURATION_MS)
            return XmltvProgramme(
                epgChannelId = channelId,
                startMs = start,
                stopMs = stop,
                title = title,
                desc = desc,
                category = category,
            )
        }
    }

    private enum class BangKind { COMMENT, CDATA, DECLARATION, UNKNOWN }

    /**
     * The reader, with the one-character pushback the scanner needs. **Forward-only by design**: no
     * `mark`/`reset`, so a test can hand it a `Reader` that refuses them and prove the parser never
     * rewinds a multi-megabyte guide.
     */
    private class CharScan(private val reader: Reader) {
        private val buffer = CharArray(BUFFER_CHARS)
        private var pos = 0
        private var len = 0
        private var pushed = -1

        fun next(): Int {
            if (pushed >= 0) {
                val value = pushed
                pushed = -1
                return value
            }
            while (pos >= len) {
                len = reader.read(buffer, 0, buffer.size)
                pos = 0
                if (len <= 0) return -1
            }
            return buffer[pos++].code
        }

        /**
         * Reads the rest of a tag (the `name="value"` part) into a string, honouring quoted attribute
         * values so a `>` inside quotes does not end the tag early. Answers null when the tag hits EOF
         * or the cap — the caller records that as a malformed document.
         */
        fun readTag(maxLength: Int, first: Char? = null): String? {
            val sb = StringBuilder()
            if (first != null) sb.append(first)
            var quote = '\u0000'
            while (true) {
                val c = next()
                if (c < 0) return null
                val ch = c.toChar()
                if (quote != '\u0000') {
                    sb.append(ch)
                    if (ch == quote) quote = '\u0000'
                    continue
                }
                if (ch == '"' || ch == '\'') {
                    quote = ch
                    sb.append(ch)
                    continue
                }
                if (ch == '>') {
                    return sb.toString()
                }
                sb.append(ch)
                if (sb.length > maxLength) return null
            }
        }

        /** After `<!`: which kind of construct this is. */
        fun peekAfterBang(): BangKind {
            val c = next()
            return when (c) {
                CHAR_DASH -> {
                    val second = next()
                    if (second == CHAR_DASH) BangKind.COMMENT else BangKind.UNKNOWN
                }

                CHAR_LBRACKET -> {
                    // `<![CDATA[` is consumed whole here (bracket included), so the CDATA *content*
                    // starts at the very next character — otherwise the text would gain a leading `[`.
                    var matched = true
                    for (expected in "CDATA") {
                        if (next() != expected.code) {
                            matched = false
                            break
                        }
                    }
                    if (matched && next() == CHAR_LBRACKET) BangKind.CDATA else BangKind.UNKNOWN
                }

                else -> {
                    if (c < 0) return BangKind.UNKNOWN
                    pushed = c
                    val rest = readWord(8)
                    if (rest.equals("DOCTYPE", ignoreCase = true)) BangKind.DECLARATION else BangKind.UNKNOWN
                }
            }
        }

        /** Reads the `[` or the letters just after `<!`; the `[` of CDATA is consumed by the caller. */
        private fun readWord(max: Int): String {
            val sb = StringBuilder()
            while (sb.length < max) {
                val c = next()
                if (c < 0) return sb.toString()
                if (c.toChar().isLetter()) sb.append(c.toChar()) else {
                    pushed = c
                    return sb.toString()
                }
            }
            return sb.toString()
        }

        fun skipPast(terminator: String): Boolean {
            var matched = 0
            while (true) {
                val c = next()
                if (c < 0) return false
                if (c.toChar() == terminator[matched]) {
                    matched++
                    if (matched == terminator.length) return true
                } else {
                    matched = if (c.toChar() == terminator[0]) 1 else 0
                }
            }
        }

        fun readUntil(terminator: String): String? {
            val sb = StringBuilder()
            var matched = 0
            while (true) {
                val c = next()
                if (c < 0) return null
                val ch = c.toChar()
                if (ch == terminator[matched]) {
                    matched++
                    if (matched == terminator.length) return sb.toString()
                } else {
                    if (matched > 0) {
                        sb.append(terminator, 0, matched)
                        matched = if (ch == terminator[0]) 1 else 0
                    }
                    if (matched == 0) sb.append(ch)
                }
            }
        }

        /** `<!DOCTYPE …>` may carry an internal subset in `[ … ]`; the `>` inside it does not end it. */
        fun skipDoctype(): Boolean {
            var depth = 0
            while (true) {
                val c = next()
                if (c < 0) return false
                when (c.toChar()) {
                    '[' -> depth++
                    ']' -> if (depth > 0) depth--
                    '>' -> if (depth == 0) return true
                }
            }
        }
    }

    /** A parsed tag head: its name, its attributes and whether it closed itself. */
    private class TagBody(
        val name: String,
        private val attributes: Map<String, String>,
        val selfClosing: Boolean,
    ) {
        fun attr(key: String): String? = attributes[key]

        companion object {
            fun parse(body: String): TagBody {
                val selfClosing = body.endsWith("/")
                val trimmed = if (selfClosing) body.dropLast(1) else body
                val nameEnd = trimmed.indexOfFirst { it.isWhitespace() }
                val name = (if (nameEnd < 0) trimmed else trimmed.substring(0, nameEnd))
                    .substringAfter(':')
                    .trim()
                return TagBody(name, parseAttributes(trimmed, nameEnd), selfClosing)
            }

            private fun parseAttributes(text: String, from: Int): Map<String, String> {
                if (from < 0 || from >= text.length) return emptyMap()
                val out = HashMap<String, String>()
                var i = from
                while (i < text.length) {
                    while (i < text.length && (text[i].isWhitespace() || text[i] == '/')) i++
                    val keyStart = i
                    while (i < text.length && isAttributeNameChar(text[i])) i++
                    if (i <= keyStart) {
                        // Not a name (a stray `=` or quote): step over it and keep scanning rather than
                        // giving up on the whole tag.
                        i++
                        continue
                    }
                    val key = text.substring(keyStart, i).substringAfter(':')
                    while (i < text.length && text[i].isWhitespace()) i++
                    if (i >= text.length || text[i] != '=') continue
                    i++
                    while (i < text.length && text[i].isWhitespace()) i++
                    if (i >= text.length) break
                    val quote = text[i]
                    if (quote != '"' && quote != '\'') continue
                    i++
                    val valueStart = i
                    while (i < text.length && text[i] != quote) i++
                    val raw = text.substring(valueStart, minOf(i, text.length))
                    if (i < text.length) i++ // closing quote
                    if (!out.containsKey(key)) out[key] = unescape(raw)
                }
                return out
            }

            private fun isAttributeNameChar(c: Char): Boolean =
                !c.isWhitespace() && c != '=' && c != '"' && c != '\'' && c != '/' && c != '<' && c != '>'
        }
    }

    companion object {
        private const val TAG_CHANNEL = "channel"
        private const val TAG_PROGRAMME = "programme"
        private const val TAG_DISPLAY_NAME = "display-name"
        private const val TAG_TITLE = "title"
        private const val TAG_DESC = "desc"
        private const val TAG_CATEGORY = "category"

        private const val CHAR_LT = '<'.code
        private const val CHAR_BANG = '!'.code
        private const val CHAR_QUESTION = '?'.code
        private const val CHAR_SLASH = '/'.code
        private const val CHAR_DASH = '-'.code
        private const val CHAR_LBRACKET = '['.code

        /** Long enough for a real programme description, short enough that one row cannot bloat us. */
        const val MAX_TEXT_LENGTH: Int = 4_096

        const val MAX_TAG_LENGTH: Int = 8_192

        /** 64 KB, matching `GzipSniffer`'s buffer: one read() per 32k chars, no per-char syscalls. */
        const val BUFFER_CHARS: Int = 64 * 1024

        /**
         * The five predefined XML entities plus numeric references. A named entity that is not one of
         * these is left as written rather than expanded — no DTD handling means no entity expansion
         * attack, and `&nbsp;` in a title is readable text, not a crash (docs/02 §12).
         */
        fun unescape(raw: String): String {
            if (raw.indexOf('&') < 0) return raw
            val sb = StringBuilder(raw.length)
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                if (c != '&') {
                    sb.append(c)
                    i++
                    continue
                }
                val end = raw.indexOf(';', i + 1)
                if (end < 0) {
                    sb.append(c)
                    i++
                    continue
                }
                val entity = raw.substring(i + 1, end)
                val decoded = when {
                    entity == "amp" -> '&'
                    entity == "lt" -> '<'
                    entity == "gt" -> '>'
                    entity == "quot" -> '"'
                    entity == "apos" -> '\''
                    entity.startsWith("#x") || entity.startsWith("#X") ->
                        entity.drop(2).toIntOrNull(16)?.takeIf { it in CODEPOINT_RANGE }?.toChar()

                    entity.startsWith("#") ->
                        entity.drop(1).toIntOrNull()?.takeIf { it in CODEPOINT_RANGE }?.toChar()

                    else -> null
                }
                if (decoded == null) {
                    sb.append(raw, i, end + 1)
                } else {
                    sb.append(decoded)
                }
                i = end + 1
            }
            return sb.toString()
        }

        private val CODEPOINT_RANGE = 0x20..0xD7FF
    }
}
