package ilab.iptv.player.core.epg

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Test

/** gzip support is decided by the body, not by the URL (docs/02 §6.3 "可 gzip"). */
class GzipSnifferTest {

    private val xml = "<tv><channel id=\"c1\"/></tv>"

    private fun gzip(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray()) }
        return out.toByteArray()
    }

    @Test
    fun `inflates a gzipped body`() {
        val decoded = GzipSniffer.decode(ByteArrayInputStream(gzip(xml)))
        assertThat(decoded.readAllText()).isEqualTo(xml)
    }

    @Test
    fun `passes a plain body through with its first bytes intact`() {
        val decoded = GzipSniffer.decode(ByteArrayInputStream(xml.toByteArray()))
        assertThat(decoded.readAllText()).isEqualTo(xml)
    }

    @Test
    fun `a body shorter than the magic is passed through unchanged`() {
        val decoded = GzipSniffer.decode(ByteArrayInputStream(byteArrayOf(0x1f)))
        assertThat(decoded.readAllBytes().size).isEqualTo(1)
    }

    @Test
    fun `a long plain body is not mistaken for gzip`() {
        val long = buildString { repeat(10_000) { append("<?xml?><tv/>") } }
        // Starts with `<`, so the magic cannot match; the point is that the sniff does not corrupt it.
        val decoded = GzipSniffer.decode(ByteArrayInputStream(long.toByteArray()))
        assertThat(decoded.readAllText()).isEqualTo(long)
    }
}
