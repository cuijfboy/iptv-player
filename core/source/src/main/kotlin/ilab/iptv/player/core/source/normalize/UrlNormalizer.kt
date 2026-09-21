package ilab.iptv.player.core.source.normalize

import java.util.Locale

/**
 * URL normalization for the Normalize stage (docs/02 §6.1: "scheme/host 小写、去默认端口、去 fragment").
 *
 * Written by hand rather than with `java.net.URI`, because real playlist URLs are frequently
 * *almost* valid (unencoded spaces, `{`, `|` in the query) and `URI` throws on them. A normalizer
 * that throws would drop working channels, so this one only rewrites what it understands and
 * leaves everything else byte-for-byte alone.
 *
 * What it changes: scheme → lower case; host → lower case; a default port for the scheme removed;
 * the `#fragment` dropped (it never reaches a server). What it keeps: the path case, the query
 * string *including* any token (`?key=…&authid=…`), and userinfo.
 */
object UrlNormalizer {

    private val schemeRe = Regex("^([A-Za-z][A-Za-z0-9+.\\-]*):")

    /** Default ports worth dropping: anything else is meaningful and kept. */
    private val defaultPorts = mapOf(
        "http" to "80",
        "https" to "443",
        "ftp" to "21",
        "rtsp" to "554",
        "rtmp" to "1935",
    )

    fun normalize(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""

        val schemeMatch = schemeRe.find(trimmed) ?: return trimmed
        val scheme = schemeMatch.groupValues[1].lowercase(Locale.ROOT)
        val afterScheme = trimmed.substring(schemeMatch.value.length)
        if (!afterScheme.startsWith("//")) return "$scheme:$afterScheme"

        val rest = afterScheme.substring(2)
        val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
            .let { if (it < 0) rest.length else it }
        val authority = rest.substring(0, authorityEnd)
        val tail = rest.substring(authorityEnd)

        val at = authority.lastIndexOf('@')
        val userinfo = if (at >= 0) authority.substring(0, at + 1) else ""
        val hostPort = if (at >= 0) authority.substring(at + 1) else authority

        // IPv6 literals are bracketed and must not be lower-cased field-by-field.
        val normalizedHostPort = if (hostPort.startsWith("[")) {
            val close = hostPort.indexOf(']')
            if (close < 0) {
                hostPort.lowercase(Locale.ROOT)
            } else {
                val host = hostPort.substring(0, close + 1).lowercase(Locale.ROOT)
                host + dropDefaultPort(hostPort.substring(close + 1), scheme)
            }
        } else {
            val colon = hostPort.lastIndexOf(':')
            if (colon < 0) {
                hostPort.lowercase(Locale.ROOT)
            } else {
                hostPort.substring(0, colon).lowercase(Locale.ROOT) +
                    dropDefaultPort(hostPort.substring(colon), scheme)
            }
        }

        val withoutFragment = tail.substringBefore('#')
        return "$scheme://$userinfo$normalizedHostPort$withoutFragment"
    }

    /** `:port` → `""` when the port is the scheme default; otherwise unchanged. */
    private fun dropDefaultPort(suffix: String, scheme: String): String {
        if (!suffix.startsWith(":")) return suffix
        val port = suffix.substring(1)
        if (port.isEmpty()) return suffix
        if (!port.all { it.isDigit() }) return suffix
        return if (defaultPorts[scheme] == port) "" else ":${port.toInt()}"
    }
}
