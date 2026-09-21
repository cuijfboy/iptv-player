package ilab.iptv.player.core.epg

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.zip.GZIPInputStream

/**
 * gzip support by **magic bytes**, not by URL or `Content-Encoding` (docs/02 §6.3 "可 gzip").
 *
 * Sniffing rather than trusting a header is the honest choice for this data: the same guide is
 * published as `epg.xml`, `epg.xml.gz` and as a plain body with `Content-Encoding: gzip` depending on
 * the mirror, and a source that changes one of those three must not turn into a parse failure. The
 * two magic bytes are read through a [PushbackInputStream] so a **plain** body is handed on with its
 * first two bytes intact.
 */
object GzipSniffer {

    private const val MAGIC_1 = 0x1f
    private const val MAGIC_2 = 0x8b

    /** Wraps [raw] in a gzip decoder when its first two bytes are the gzip magic. */
    fun decode(raw: InputStream): InputStream {
        val buffered = if (raw is BufferedInputStream) raw else BufferedInputStream(raw, BUFFER_SIZE)
        val pushback = PushbackInputStream(buffered, 2)
        val first = pushback.read()
        if (first < 0) return pushback
        val second = pushback.read()
        if (second < 0) {
            pushback.unread(first)
            return pushback
        }
        if (first == MAGIC_1 && second == MAGIC_2) {
            // GZIPInputStream re-reads the header, so the two sniffed bytes go back.
            pushback.unread(second)
            pushback.unread(first)
            return GZIPInputStream(pushback, BUFFER_SIZE)
        }
        pushback.unread(second)
        pushback.unread(first)
        return pushback
    }

    /** 64 KB: large enough that a multi-megabyte guide is not chunked into per-byte reads. */
    const val BUFFER_SIZE: Int = 64 * 1024
}
