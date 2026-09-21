package ilab.iptv.player.core.data.playlist

/**
 * The "remember the last import" record: which file was imported, where the kept copy lives, and
 * what it turned into.
 *
 * It is written as one small JSON object (`last-import.json`) next to the copy, because the app has
 * no settings store yet and adding a serialization dependency for eight fields would be the tail
 * wagging the dog. The decoder is strict in one direction only: anything missing, malformed or of
 * the wrong type makes [decode] return null, which the caller reads as "no remembered import" and
 * falls back to the bundled fixture. A corrupt record must never keep the app from starting.
 */
data class ImportRecord(
    val name: String,
    val sourceId: String,
    val copiedPath: String,
    val sizeBytes: Long,
    val importedAtMs: Long,
    val formatLabel: String,
    val channels: Int,
    val streams: Int,
    /**
     * The `content://…` document this import came from, when it came from the system picker rather
     * than the drop folder (P2-6 item 3). Optional: a record written before SAF existed has no such
     * field, and [decode] answers null for it instead of refusing the whole record.
     */
    val sourceUri: String? = null,
) {
    fun encode(): String = buildString {
        append('{')
        appendField(VERSION_KEY, VERSION.toString())
        appendField("name", name)
        appendField("sourceId", sourceId)
        appendField("copiedPath", copiedPath)
        appendField("sizeBytes", sizeBytes.toString())
        appendField("importedAtMs", importedAtMs.toString())
        appendField("formatLabel", formatLabel)
        appendField("channels", channels.toString())
        appendField("streams", streams.toString())
        // Written only when set, so an existing version-1 record stays byte-identical.
        if (sourceUri != null) appendField("sourceUri", sourceUri)
        append('}')
    }

    private fun StringBuilder.appendField(key: String, value: String) {
        if (length > 1) append(',')
        append('"').append(escape(key)).append("\":\"").append(escape(value)).append('"')
    }

    companion object {
        /** Bumped only when the shape changes in a way an older field-set cannot mean. */
        const val VERSION = 1

        private const val VERSION_KEY = "version"

        /**
         * Parses one object; null when the text is not a version-1 object with every field present
         * and well-typed. Numbers travel as strings in this format (see [encode]) so a `name` with a
         * quote in it and an `importedAtMs` can be read by the same code.
         */
        fun decode(json: String): ImportRecord? {
            val fields = parseObject(json) ?: return null
            if (fields[VERSION_KEY] != VERSION.toString()) return null
            return ImportRecord(
                name = fields["name"]?.takeIf { it.isNotBlank() } ?: return null,
                sourceId = fields["sourceId"]?.takeIf { it.isNotBlank() } ?: return null,
                copiedPath = fields["copiedPath"]?.takeIf { it.isNotBlank() } ?: return null,
                sizeBytes = fields["sizeBytes"]?.toLongOrNull()?.takeIf { it >= 0 } ?: return null,
                importedAtMs = fields["importedAtMs"]?.toLongOrNull() ?: return null,
                formatLabel = fields["formatLabel"] ?: "unknown",
                channels = fields["channels"]?.toIntOrNull() ?: 0,
                streams = fields["streams"]?.toIntOrNull() ?: 0,
                // Absent in records written before SAF support, so null is the valid answer.
                sourceUri = fields["sourceUri"]?.takeIf { it.isNotBlank() },
            )
        }

        private fun escape(value: String): String = buildString(value.length) {
            for (ch in value) {
                when (ch) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
                }
            }
        }

        /**
         * A minimal JSON object reader: flat string values only, which is the entire shape this file
         * has. Anything it does not understand makes it return null instead of guessing.
         */
        private fun parseObject(json: String): Map<String, String>? {
            var i = 0
            fun skipSpace() {
                while (i < json.length && json[i].isWhitespace()) i++
            }

            fun readString(): String? {
                if (i >= json.length || json[i] != '"') return null
                i++
                val out = StringBuilder()
                while (i < json.length) {
                    val ch = json[i++]
                    when {
                        ch == '"' -> return out.toString()
                        ch == '\\' -> {
                            if (i >= json.length) return null
                            val esc = json[i++]
                            when (esc) {
                                '"' -> out.append('"')
                                '\\' -> out.append('\\')
                                '/' -> out.append('/')
                                'b' -> out.append('\b')
                                'f' -> out.append('\u000C')
                                'n' -> out.append('\n')
                                'r' -> out.append('\r')
                                't' -> out.append('\t')
                                'u' -> {
                                    if (i + 4 > json.length) return null
                                    val code = json.substring(i, i + 4).toIntOrNull(16) ?: return null
                                    out.append(code.toChar())
                                    i += 4
                                }

                                else -> return null
                            }
                        }

                        else -> out.append(ch)
                    }
                }
                return null
            }

            skipSpace()
            if (i >= json.length || json[i] != '{') return null
            i++
            val fields = LinkedHashMap<String, String>()
            skipSpace()
            if (i < json.length && json[i] == '}') return fields
            while (true) {
                skipSpace()
                val key = readString() ?: return null
                skipSpace()
                if (i >= json.length || json[i] != ':') return null
                i++
                skipSpace()
                val value = if (i < json.length && json[i] == '"') {
                    readString() ?: return null
                } else {
                    val start = i
                    while (i < json.length && json[i] != ',' && json[i] != '}') i++
                    json.substring(start, i).trim().takeIf { it.isNotEmpty() } ?: return null
                }
                fields[key] = value
                skipSpace()
                if (i >= json.length) return null
                when (json[i]) {
                    ',' -> i++
                    '}' -> return fields
                    else -> return null
                }
            }
        }
    }
}
