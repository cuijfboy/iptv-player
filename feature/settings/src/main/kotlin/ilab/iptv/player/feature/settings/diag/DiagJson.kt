package ilab.iptv.player.feature.settings.diag

import ilab.iptv.player.core.common.Redactor

/**
 * The JSON writer for the export package's `meta.json` and `stats.json` (docs/03 §8).
 *
 * Hand-rolled for the same reason as `core:log`'s `LogJsonLine`: `org.json` is an Android stub that
 * throws on a JVM unit test, and the escaping is exactly what the export's own test has to assert.
 *
 * [redactor] runs over every string **on the way into the document** — the export pipeline re-runs
 * redaction at its entry (docs/03 §11/W7), so a value that somehow reached the writer unredacted is
 * still masked before it lands in the zip.
 */
object DiagJson {

    fun encode(value: Any?, redactor: Redactor? = null): String {
        val builder = StringBuilder(256)
        append(builder, value, redactor)
        return builder.toString()
    }

    private fun append(builder: StringBuilder, value: Any?, redactor: Redactor?) {
        when (value) {
            null -> builder.append("null")
            is String -> appendString(builder, value, redactor)
            is Boolean -> builder.append(value.toString())
            is Byte, is Short, is Int, is Long -> builder.append(value.toString())
            is Float -> builder.append(if (value.isFinite()) value.toString() else "null")
            is Double -> builder.append(if (value.isFinite()) value.toString() else "null")
            is Number -> builder.append(value.toString())
            is Map<*, *> -> appendObject(builder, value, redactor)
            is Iterable<*> -> appendArray(builder, value, redactor)
            is Array<*> -> appendArray(builder, value.asList(), redactor)
            else -> appendString(builder, value.toString(), redactor)
        }
    }

    private fun appendObject(builder: StringBuilder, value: Map<*, *>, redactor: Redactor?) {
        builder.append('{')
        var first = true
        value.forEach { (key, entryValue) ->
            if (!first) builder.append(',')
            first = false
            appendString(builder, key?.toString().orEmpty(), redactor)
            builder.append(':')
            append(builder, entryValue, redactor)
        }
        builder.append('}')
    }

    private fun appendArray(builder: StringBuilder, value: Iterable<*>, redactor: Redactor?) {
        builder.append('[')
        var first = true
        value.forEach { element ->
            if (!first) builder.append(',')
            first = false
            append(builder, element, redactor)
        }
        builder.append(']')
    }

    private fun appendString(builder: StringBuilder, value: String, redactor: Redactor?) {
        val safe = redactor?.redact(value) ?: value
        builder.append('"')
        safe.forEach { char ->
            when (char) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
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
