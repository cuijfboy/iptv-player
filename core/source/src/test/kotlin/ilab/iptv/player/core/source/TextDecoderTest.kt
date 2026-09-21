package ilab.iptv.player.core.source

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.source.text.TextDecoder
import org.junit.Test

class TextDecoderTest {

    @Test
    fun `plain utf8 decodes without a fallback`() {
        val bytes = "央视一套,http://a.example/1.m3u8\n".toByteArray(Charsets.UTF_8)

        val decoded = TextDecoder.decode(bytes)

        assertThat(decoded.charset).isEqualTo("UTF-8")
        assertThat(decoded.fallbackFromUtf8).isFalse()
        assertThat(decoded.lossy).isFalse()
        assertThat(decoded.text).contains("央视一套")
    }

    @Test
    fun `gb18030 bytes fall back and are decoded, not mangled`() {
        val bytes = Fixtures.bytes("m3u/gb18030.m3u")

        val decoded = TextDecoder.decode(bytes)

        assertThat(decoded.charset).isEqualTo("GB18030")
        assertThat(decoded.fallbackFromUtf8).isTrue()
        assertThat(decoded.lossy).isFalse()
        assertThat(decoded.text).contains("央视一套")
        assertThat(decoded.text).contains("#EXTM3U")
    }

    @Test
    fun `utf8 bom is stripped and crlf stays decodable`() {
        val bytes = Fixtures.bytes("m3u/bom-crlf.m3u")

        val decoded = TextDecoder.decode(bytes)

        assertThat(decoded.charset).isEqualTo("UTF-8")
        assertThat(decoded.fallbackFromUtf8).isFalse()
        assertThat(decoded.text.startsWith("#EXTM3U")).isTrue()
    }

    @Test
    fun `an explicit hint wins over detection`() {
        val bytes = Fixtures.bytes("m3u/gb18030.m3u")

        val decoded = TextDecoder.decode(bytes, charsetHint = "GB18030")

        assertThat(decoded.charset).isEqualTo("GB18030")
        assertThat(decoded.fallbackFromUtf8).isFalse()
        assertThat(decoded.text).contains("央视一套")
    }

    @Test
    fun `bytes that are not valid gb18030 either still come back, marked lossy`() {
        // 0x81 0x30 is not a GB18030 sequence, and 0xFF is never valid in it.
        val bytes = byteArrayOf(0x81.toByte(), 0x30, 0x41, 0xFF.toByte(), 0x42)

        val decoded = TextDecoder.decode(bytes)

        assertThat(decoded.fallbackFromUtf8).isTrue()
        assertThat(decoded.lossy).isTrue()
        assertThat(decoded.text).contains("B")
    }

    @Test
    fun `empty input is empty text, not a failure`() {
        val decoded = TextDecoder.decode(ByteArray(0))

        assertThat(decoded.text).isEmpty()
        assertThat(decoded.fallbackFromUtf8).isFalse()
    }
}
