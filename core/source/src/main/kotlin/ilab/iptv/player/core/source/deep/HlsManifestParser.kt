package ilab.iptv.player.core.source.deep

import java.net.URI

/**
 * Minimal, tolerant HLS playlist reader (docs/02 §6.1 Deep).
 *
 * Tolerant on purpose, in the same spirit as the M3U/TXT parser (docs/02 §6.1 "解析器绝不因未知行/
 * 未知属性中断整份清单"): unknown tags and malformed attribute lists are skipped, a tag with no URI
 * line under it is skipped, and the parser only answers `null` when the body is not a playlist at
 * all (no `#EXTM3U` first line).
 */
object HlsManifestParser {

    private const val HEADER = "#EXTM3U"
    private const val STREAM_INF = "#EXT-X-STREAM-INF:"
    private const val EXTINF = "#EXTINF:"
    private const val MEDIA_SEQUENCE = "#EXT-X-MEDIA-SEQUENCE:"

    /**
     * @param text the fetched playlist body;
     * @param baseUri the URL it came from, used to resolve relative variant/segment URIs.
     * @return the manifest, or null when [text] is not an HLS playlist.
     */
    fun parse(text: String, baseUri: String): HlsManifest? {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty() || !lines.first().startsWith(HEADER)) return null

        val variants = ArrayList<HlsVariant>()
        val segments = ArrayList<String>()
        var mediaSequence: Long? = null
        var pending: PendingUri = PendingUri.NONE
        var pendingAttributes: Map<String, String> = emptyMap()

        for (line in lines.drop(1)) {
            when {
                line.startsWith(STREAM_INF) -> {
                    pending = PendingUri.VARIANT
                    pendingAttributes = attributes(line.removePrefix(STREAM_INF))
                }
                line.startsWith(EXTINF) -> pending = PendingUri.SEGMENT
                line.startsWith(MEDIA_SEQUENCE) ->
                    mediaSequence = line.removePrefix(MEDIA_SEQUENCE).trim().toLongOrNull()
                // Any other tag (including `#EXT-X-KEY`, `#EXT-X-ENDLIST`, vendor tags) is skipped;
                // the URI that follows a tag with no URI line must not be mistaken for a segment.
                line.startsWith("#") -> Unit
                else -> {
                    val uri = resolve(baseUri, line)
                    when (pending) {
                        PendingUri.VARIANT -> variants += HlsVariant(
                            uri = uri,
                            bandwidth = pendingAttributes["BANDWIDTH"]?.toLongOrNull(),
                            width = pendingAttributes["RESOLUTION"]?.substringBefore('x')?.trim()?.toIntOrNull() ?: 0,
                            height = pendingAttributes["RESOLUTION"]?.substringAfter('x')?.trim()?.toIntOrNull() ?: 0,
                            codecs = (pendingAttributes["CODECS"] ?: "")
                                .split(',')
                                .map { it.trim() }
                                .filter { it.isNotEmpty() },
                        )
                        // A bare URI with no `#EXTINF` before it: some encoders emit those for live
                        // media playlists, and a segment we can fetch is exactly what the deep probe
                        // is looking for (docs/02 §6.1 edge slice).
                        PendingUri.SEGMENT, PendingUri.NONE -> segments += uri
                    }
                    pending = PendingUri.NONE
                }
            }
        }
        return HlsManifest(
            isMaster = variants.isNotEmpty(),
            variants = variants,
            segments = segments,
            mediaSequence = mediaSequence,
        )
    }

    /**
     * Resolves [uri] against [baseUri] (RFC 3986, as `java.net.URI` implements it). A malformed pair
     * returns the raw [uri]: a probe that then fails to fetch reports a transport error rather than
     * throwing out of the validator (docs/02 §4.0 F2).
     */
    fun resolve(baseUri: String, uri: String): String = try {
        URI(baseUri).resolve(uri).toString()
    } catch (_: Exception) {
        uri
    }

    /** Splits an attribute list on commas that are **not** inside a quoted value (`CODECS="a,b"`). */
    fun attributes(raw: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var i = 0
        val current = StringBuilder()
        var inQuotes = false
        while (i <= raw.length) {
            val c = if (i < raw.length) raw[i] else ','
            when {
                c == '"' -> { inQuotes = !inQuotes; i++ }
                c == ',' && !inQuotes -> {
                    putAttribute(out, current.toString())
                    current.setLength(0)
                    i++
                }
                else -> { current.append(c); i++ }
            }
        }
        return out
    }

    private fun putAttribute(out: MutableMap<String, String>, token: String) {
        val key = token.substringBefore('=', "").trim()
        val value = token.substringAfter('=', "").trim().trim('"')
        if (key.isNotEmpty() && value.isNotEmpty()) out[key.uppercase()] = value
    }

    private enum class PendingUri { NONE, VARIANT, SEGMENT }
}
