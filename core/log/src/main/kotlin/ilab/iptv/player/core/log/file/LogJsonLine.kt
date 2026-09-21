package ilab.iptv.player.core.log.file

import ilab.iptv.player.core.common.LogEvent
import ilab.iptv.player.core.common.Redactor

/**
 * Serialises one [LogEvent] into one JSONL line (docs/03 §4 "日志导出为 JSONL（每行一个 JSON
 * 对象）", §5 `FileSink`).
 *
 * The documented keys and their order come first — `seq`, `ts`, `level`, `category`, `code`,
 * `message`, `fields`, `sessionId` (docs/03 §4 shows exactly that line) — and the remaining
 * envelope members the event model carries are appended after it: `elapsedMs` and `thread`
 * (stall detection and thread attribution are the whole point of a post-mortem file),
 * `screen` only when set, and `error` (`{type, message, stack}`) only when the event carries a
 * throwable. Extra keys do not break a JSONL reader such as `jq`, and dropping them would make the
 * on-disk copy strictly worse than the ring buffer it is supposed to outlive.
 *
 * Hand-rolled instead of `org.json` on purpose: the encoder is pure Kotlin, so the escaping and the
 * field rendering are unit-tested on the JVM (`org.json` is an Android stub that throws off-device).
 * [redactor] runs here over the strings the bus does not touch (an error stack), so a line on disk
 * is redacted even if a caller ever bypasses the bus.
 */
object LogJsonLine {

    fun encode(event: LogEvent, redactor: Redactor? = null): String {
        val builder = StringBuilder(256)
        builder.append('{')
        appendKey(builder, "seq"); appendLong(builder, event.seq)
        appendKey(builder, "ts"); appendLong(builder, event.ts)
        appendKey(builder, "level"); appendString(builder, event.level.name, redactor)
        appendKey(builder, "category"); appendString(builder, event.category.name, redactor)
        appendKey(builder, "code"); appendString(builder, event.code, redactor)
        appendKey(builder, "message"); appendString(builder, event.message, redactor)
        appendKey(builder, "fields"); appendValue(builder, event.fields, redactor)
        appendKey(builder, "sessionId"); appendString(builder, event.sessionId, redactor)
        appendKey(builder, "elapsedMs"); appendLong(builder, event.elapsedMs)
        appendKey(builder, "thread"); appendString(builder, event.thread, redactor)
        event.screen?.let {
            appendKey(builder, "screen")
            appendString(builder, it, redactor)
        }
        event.error?.let { error ->
            appendKey(builder, "error")
            builder.append('{')
            appendKey(builder, "type"); appendString(builder, error.javaClass.name, redactor)
            appendKey(builder, "message"); appendString(builder, error.message.orEmpty(), redactor)
            appendKey(builder, "stack"); appendString(builder, error.stackTraceToString(), redactor)
            builder.append('}')
        }
        builder.append('}')
        return builder.toString()
    }

    /** Renders any field value the way a `jq` reader expects it (docs/03 §4 `fields`). */
    internal fun appendValue(builder: StringBuilder, value: Any?, redactor: Redactor?) {
        when (value) {
            null -> builder.append("null")
            is String -> appendString(builder, value, redactor)
            is Boolean -> builder.append(value.toString())
            is Byte, is Short, is Int, is Long -> builder.append(value.toString())
            is Float -> if (value.isFinite()) builder.append(value.toString()) else appendString(builder, value.toString(), redactor)
            is Double -> if (value.isFinite()) builder.append(value.toString()) else appendString(builder, value.toString(), redactor)
            is Number -> builder.append(value.toString())
            is Char -> appendString(builder, value.toString(), redactor)
            is Map<*, *> -> appendObject(builder, value, redactor)
            is Iterable<*> -> appendArray(builder, value, redactor)
            is Array<*> -> appendArray(builder, value.asList(), redactor)
            is BooleanArray -> appendArray(builder, value.toList(), redactor)
            is IntArray -> appendArray(builder, value.toList(), redactor)
            is LongArray -> appendArray(builder, value.toList(), redactor)
            is DoubleArray -> appendArray(builder, value.toList(), redactor)
            is FloatArray -> appendArray(builder, value.toList(), redactor)
            is ShortArray -> appendArray(builder, value.toList(), redactor)
            is ByteArray -> appendArray(builder, value.toList(), redactor)
            is CharArray -> appendArray(builder, value.toList(), redactor)
            else -> appendString(builder, value.toString(), redactor)
        }
    }

    private fun appendObject(builder: StringBuilder, value: Map<*, *>, redactor: Redactor?) {
        builder.append('{')
        value.entries.forEach { entry ->
            // No separator here: `appendKey` adds one whenever the builder is not right after '{'.
            appendKey(builder, entry.key?.toString().orEmpty())
            appendValue(builder, entry.value, redactor)
        }
        builder.append('}')
    }

    private fun appendArray(builder: StringBuilder, value: Iterable<*>, redactor: Redactor?) {
        builder.append('[')
        value.forEachIndexed { index, element ->
            if (index > 0) builder.append(',')
            appendValue(builder, element, redactor)
        }
        builder.append(']')
    }

    private fun appendKey(builder: StringBuilder, key: String) {
        if (builder.length > 1 && builder.last() != '{') builder.append(',')
        appendQuoted(builder, key)
        builder.append(':')
    }

    private fun appendLong(builder: StringBuilder, value: Long) {
        builder.append(value.toString())
    }

    private fun appendString(builder: StringBuilder, value: String, redactor: Redactor?) {
        appendQuoted(builder, redactor?.redact(value) ?: value)
    }

    private fun appendQuoted(builder: StringBuilder, value: String) {
        builder.append('"')
        value.forEach { char ->
            when (char) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                '\b' -> builder.append("\\b")
                '\u000C' -> builder.append("\\f")
                else -> if (char < ' ') {
                    builder.append("\\u").append(char.code.toString(16).padStart(4, '0'))
                } else {
                    builder.append(char)
                }
            }
        }
        builder.append('"')
    }
}
