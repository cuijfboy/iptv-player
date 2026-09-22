package ilab.iptv.player.core.log

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UrlRedactorTest {

    private val redactor = UrlRedactor()

    @Test
    fun `masks sensitive query parameters and keeps the rest`() {
        val redacted = redactor.redact("http://cdn.example.com/live/a.m3u8?token=abc123&id=7&Key=zzz")

        // The path became its hash (docs/03 §11 rule 2); the query keeps everything but the secrets.
        assertThat(redacted).matches("""http://cdn\.example\.com/[0-9a-f]{8}\?token=\*\*\*&id=7&Key=\*\*\*""")
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
            .matches("""rtsp://\*\*\*@10\.0\.0\.9:554/[0-9a-f]{8}""")
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

    // --- docs/03 §11 rule 2: the path is where a portal hides the account and the stream key ------

    @Test
    fun `replaces a path that carries a credential with its hash`() {
        val redacted = redactor.redact("http://portal.example.com/live/user123/secretkey/1.m3u8")

        assertThat(redacted).matches("""http://portal\.example\.com/[0-9a-f]{8}""")
        assertThat(redacted).doesNotContain("user123")
        assertThat(redacted).doesNotContain("secretkey")
        assertThat(redacted).doesNotContain("1.m3u8")
    }

    @Test
    fun `keeps the host, the port and a bare slash`() {
        assertThat(redactor.redact("http://h:8080/")).isEqualTo("http://h:8080/")
        assertThat(redactor.redact("http://h")).isEqualTo("http://h")
    }

    @Test
    fun `the same path always hashes the same way and different paths do not collide`() {
        val a = redactor.redact("http://h/live/a.m3u8")
        val b = redactor.redact("http://h/live/a.m3u8")
        val c = redactor.redact("http://h/live/b.m3u8")

        assertThat(a).isEqualTo(b)
        assertThat(a).isNotEqualTo(c)
    }

    @Test
    fun `redacting an already redacted url changes nothing`() {
        val once = redactor.redact("http://portal/live/user/secret-key/2.m3u8?token=abc")
        val twice = redactor.redact(once)

        // The export pipeline (§8) runs the redactor a second time over lines the bus already wrote;
        // that second pass must be a no-op, or the package and the console would disagree.
        assertThat(twice).isEqualTo(once)
    }
}
