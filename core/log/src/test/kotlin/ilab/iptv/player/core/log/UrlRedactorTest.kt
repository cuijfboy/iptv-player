package ilab.iptv.player.core.log

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UrlRedactorTest {

    private val redactor = UrlRedactor()

    @Test
    fun `masks sensitive query parameters and keeps the rest`() {
        val redacted = redactor.redact("http://cdn.example.com/live/a.m3u8?token=abc123&id=7&Key=zzz")

        assertThat(redacted).isEqualTo("http://cdn.example.com/live/a.m3u8?token=***&id=7&Key=***")
    }

    @Test
    fun `covers every key docs 03 section 11 lists`() {
        val keys = listOf("token", "key", "pwd", "pass", "sign", "auth", "secret", "expire")

        keys.forEach { key ->
            assertThat(redactor.redact("http://h/?$key=leak")).isEqualTo("http://h/?$key=***")
        }
    }

    @Test
    fun `masks credentials embedded in the authority`() {
        assertThat(redactor.redact("rtsp://user:pw@10.0.0.9:554/live"))
            .isEqualTo("rtsp://***@10.0.0.9:554/live")
    }

    @Test
    fun `finds credentials inside surrounding prose`() {
        val redacted = redactor.redact("fetch failed url=http://h/x?token=abc&user=42 retry=1")

        assertThat(redacted).contains("token=***")
        assertThat(redacted).doesNotContain("abc")
        assertThat(redacted).contains("user=42")
    }

    @Test
    fun `leaves unrelated text and blank input alone`() {
        assertThat(redactor.redact("")).isEmpty()
        assertThat(redactor.redact("keyboard=layout, monkey=1"))
            .isEqualTo("keyboard=layout, monkey=1")
    }
}
