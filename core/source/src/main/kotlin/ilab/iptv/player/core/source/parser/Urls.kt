package ilab.iptv.player.core.source.parser

/**
 * URL shape test used by both parsers before an entry is accepted.
 *
 * Deliberately permissive: any scheme (`http`, `https`, `rtsp`, `rtmp`, `udp`, `rtp`, `file`, …)
 * followed by `://` and at least one non-space character counts as usable. The parser's job is to
 * reject rows a player could never dial (`not a url`, `http://`, empty); quality, reachability and
 * codec support are decided later by the Shallow/Deep stages, not here.
 */
internal object Urls {

    private val schemeUrl = Regex("^[A-Za-z][A-Za-z0-9+.\\-]*://\\S+$")

    fun isValid(url: String): Boolean = schemeUrl.matches(url)

    /**
     * The URL part of a line that may carry trailing hints. Both the Kodi/IPTV convention
     * `url|User-Agent=…` and a plain trailing space are cut off; a `|` never belongs to the URL a
     * player dials (it must be percent-encoded as `%7C` if it is meant literally).
     */
    fun extract(line: String): String {
        val trimmed = line.trim()
        val cut = trimmed.indexOfFirst { it.isWhitespace() || it == '|' }
        return if (cut < 0) trimmed else trimmed.substring(0, cut)
    }
}
