package ilab.iptv.player.refresh

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.domain.refresh.RefreshScheduleSettings
import java.util.Calendar
import java.util.TimeZone
import org.junit.Test

/**
 * The schedule arithmetic of docs/04 P2-5 item 1 ("默认每日 06:00"): the WorkManager request is a
 * 24 h period plus *this* delay, so an off-by-one here is a refresh that runs at the wrong hour or
 * twice in a row.
 */
class RefreshScheduleTest {

    private val zone: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")

    private fun local(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0, ms: Int = 0): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(year, month - 1, day, hour, minute, second)
            set(Calendar.MILLISECOND, ms)
        }.timeInMillis

    @Test
    fun `before the target the delay is the time left today`() {
        val now = local(2026, 9, 22, 5, 0)
        assertThat(RefreshSchedule.nextDelayMs(now, 6 * 60, zone)).isEqualTo(60L * 60_000L)
    }

    @Test
    fun `exactly on the target the delay is a whole day`() {
        val now = local(2026, 9, 22, 6, 0, 0, 0)
        assertThat(RefreshSchedule.nextDelayMs(now, 6 * 60, zone)).isEqualTo(RefreshSchedule.PERIOD_MS)
    }

    @Test
    fun `a millisecond past the target is tomorrow minus that millisecond`() {
        val now = local(2026, 9, 22, 6, 0, 0, 1)
        assertThat(RefreshSchedule.nextDelayMs(now, 6 * 60, zone)).isEqualTo(RefreshSchedule.PERIOD_MS - 1)
    }

    @Test
    fun `after the target the delay rolls over to tomorrow`() {
        val now = local(2026, 9, 22, 23, 59)
        assertThat(RefreshSchedule.nextDelayMs(now, 6 * 60, zone)).isEqualTo(6L * 60 * 60_000L + 60_000L)
    }

    @Test
    fun `a late-evening target resolves to the same day, not the next one`() {
        val now = local(2026, 9, 22, 0, 30)
        val target = 23 * 60 + 59
        assertThat(RefreshSchedule.nextDelayMs(now, target, zone)).isEqualTo(23L * 60 * 60_000L + 29L * 60_000L)
    }

    @Test
    fun `a garbage setting falls back to the 06 00 default`() {
        val now = local(2026, 9, 22, 5, 0)
        assertThat(RefreshSchedule.nextDelayMs(now, 2_000, zone)).isEqualTo(60L * 60_000L)
        assertThat(RefreshSchedule.nextDelayMs(now, -5, zone))
            .isEqualTo(RefreshSchedule.nextDelayMs(now, RefreshScheduleSettings.DEFAULT_MINUTE_OF_DAY, zone))
    }

    @Test
    fun `the delay is always positive and never longer than a day`() {
        var now = local(2026, 1, 1, 0, 0)
        repeat(24 * 7) {
            val delay = RefreshSchedule.nextDelayMs(now, 6 * 60, zone)
            assertThat(delay).isGreaterThan(0L)
            assertThat(delay).isAtMost(RefreshSchedule.PERIOD_MS)
            now += 37L * 60_000L
        }
    }

    @Test
    fun `the flex window is a real time next to the target, not earlier`() {
        // WorkManager runs anywhere in [delay, delay + flex]; the target hour must therefore be the
        // *lower* bound of the window (docs/04 P2-5: 06:00 means "just after 06:00").
        assertThat(RefreshSchedule.FLEX_MS).isAtLeast(5L * 60_000L)
        assertThat(RefreshSchedule.FLEX_MS).isLessThan(60L * 60_000L)
    }
}
