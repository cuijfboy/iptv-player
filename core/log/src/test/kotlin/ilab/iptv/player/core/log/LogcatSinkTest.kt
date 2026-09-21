package ilab.iptv.player.core.log

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.LogEvent
import ilab.iptv.player.core.common.LogLevel
import org.junit.Test

/**
 * `android.util.Log` is stubbed on the unit-test JVM (`unitTests.isReturnDefaultValues = true`), so
 * this only checks that formatting and error printing do not throw — the real output is verified on
 * the TV with an `adb logcat` tag filter (see docs/05-过程记录/05-P0构建验证.md, P0-6).
 */
class LogcatSinkTest {

    @Test
    fun `write tolerates every level including a throwable`() {
        val sink = LogcatSink()

        LogLevel.entries.forEach { level ->
            sink.write(event(level, error = if (level == LogLevel.ERROR) IllegalStateException("boom") else null))
        }
        sink.flush()

        assertThat(sink.id).isEqualTo(LogcatSink.ID)
        assertThat(LogcatSink.TAG_PREFIX).isEqualTo("IPTV")
    }

    private fun event(level: LogLevel, error: Throwable?): LogEvent = LogEvent(
        seq = 1L,
        ts = 1_700_000_000_000L,
        elapsedMs = 1234L,
        level = level,
        category = LogCategory.NET,
        code = "NET_REQ_FAIL",
        message = "request failed",
        fields = mapOf("host" to "cdn.example.com", "ms" to 812),
        error = error,
        thread = "test",
        sessionId = "app-0000",
    )
}
