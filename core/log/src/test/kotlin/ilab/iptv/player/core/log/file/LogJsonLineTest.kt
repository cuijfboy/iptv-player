package ilab.iptv.player.core.log.file

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.LogLevel
import ilab.iptv.player.core.log.UrlRedactor
import org.junit.Test

/** The on-disk line format of docs/03 §4 ("每行一个 JSON 对象"), pinned as a string. */
class LogJsonLineTest {

    @Test
    fun `encodes the documented keys in the documented order`() {
        val line = LogJsonLine.encode(
            testEvent(
                seq = 1024L,
                category = LogCategory.PLAYER,
                code = "PLAY_FIRST_FRAME",
                message = "first frame",
                fields = mapOf(
                    "channelId" to 12,
                    "streamId" to 88L,
                    "ok" to true,
                    "ratio" to 1.5,
                    "none" to null,
                    "tags" to listOf("a", "b"),
                    "nested" to mapOf("x" to 1),
                ),
                sessionId = "play-7f3a",
            ),
        )

        assertThat(line).isEqualTo(
            """{"seq":1024,"ts":1700000001024,"level":"INFO","category":"PLAYER","code":"PLAY_FIRST_FRAME",""" +
                """"message":"first frame","fields":{"channelId":12,"streamId":88,"ok":true,"ratio":1.5,""" +
                """"none":null,"tags":["a","b"],"nested":{"x":1}},"sessionId":"play-7f3a",""" +
                """"elapsedMs":10240,"thread":"iptv-log"}""",
        )
    }

    @Test
    fun `escapes quotes backslashes newlines and control characters`() {
        val line = LogJsonLine.encode(
            testEvent(message = "he said \"hi\"\\path\nline\ttab\u0001"),
        )

        assertThat(line).contains("he said \\\"hi\\\"")
        assertThat(line).contains("\\\\path")
        assertThat(line).contains("\\nline")
        assertThat(line).contains("\\ttab")
        assertThat(line).contains("\\u0001")
        assertThat(line).doesNotContain("\n")
    }

    @Test
    fun `non finite doubles become strings instead of invalid json`() {
        val line = LogJsonLine.encode(testEvent(fields = mapOf("inf" to Double.POSITIVE_INFINITY)))

        assertThat(line).contains(""""inf":"Infinity"""")
    }

    @Test
    fun `unknown field types fall back to their string form`() {
        val line = LogJsonLine.encode(testEvent(fields = mapOf("code" to LogLevel.WARN, "sb" to StringBuilder("x"))))

        assertThat(line).contains(""""code":"WARN"""")
        assertThat(line).contains(""""sb":"x"""")
    }

    @Test
    fun `an error carries type message and stack`() {
        val error = IllegalStateException("boom")

        val line = LogJsonLine.encode(testEvent(error = error))

        assertThat(line).contains(""""error":{"type":"java.lang.IllegalStateException","message":"boom","stack":"java.lang.IllegalStateException: boom""")
    }

    @Test
    fun `screen is only present when set`() {
        assertThat(LogJsonLine.encode(testEvent(screen = "Main"))).contains(""""screen":"Main"""")
        assertThat(LogJsonLine.encode(testEvent(screen = null))).doesNotContain("screen")
    }

    @Test
    fun `the redactor runs over the message and the error stack`() {
        val line = LogJsonLine.encode(
            testEvent(
                message = "fetch https://host/live.m3u8?token=SECRET",
                error = IllegalStateException("failed https://user:pw@host/x"),
            ),
            UrlRedactor(),
        )

        assertThat(line).doesNotContain("SECRET")
        assertThat(line).doesNotContain("user:pw")
        assertThat(line).contains("token=***")
    }

    @Test
    fun `non ascii text is kept as text and survives utf8 encoding`() {
        val line = LogJsonLine.encode(testEvent(message = "频道 12 起播"))

        assertThat(line).contains("频道 12 起播")
        assertThat(line.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8)).isEqualTo(line)
    }
}
