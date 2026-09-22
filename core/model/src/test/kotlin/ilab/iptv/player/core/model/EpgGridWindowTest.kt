package ilab.iptv.player.core.model

import com.google.common.truth.Truth.assertThat
import java.util.Calendar
import java.util.TimeZone
import org.junit.Test

/**
 * The single definition of "the window the grid shows" (BUG-20260922-018) and the gate's reading of the
 * stored guide (BUG-20260922-016), both as plain data — the behaviours that depend on them are tested
 * where they live (`WindowPlannerTest`, `EpgRefreshPolicyTest`, `EpgWindowAlignmentTest`).
 */
class EpgGridWindowTest {

    private val zone = TimeZone.getTimeZone("Asia/Shanghai")
    private val now: Long = Calendar.getInstance(zone).apply {
        clear()
        set(2026, 5, 15, 10, 7, 30)
    }.timeInMillis

    @Test
    fun `the opening window is six hours wide, half an hour of history, aligned to the ruler`() {
        val window = EpgGridWindow.of(now, zone)

        assertThat(window.fromMs).isEqualTo(EpgGridWindow.floorToStep(now - EpgGridWindow.HISTORY_MS, 30, zone))
        assertThat(window.toMs - window.fromMs).isEqualTo(EpgGridWindow.SPAN_MS)
        // 10:07:30 − 30 min = 09:37:30 → floored to 09:30, and "now" sits inside it.
        assertThat(window.fromMs).isEqualTo(epochAt(9, 30))
        assertThat(window.toMs).isEqualTo(epochAt(15, 30))
        assertThat(window.overlaps(now, now + 60_000L)).isTrue()
    }

    @Test
    fun `the overlap predicate is the one the DAO and the grid use`() {
        val window = EpgGridWindow.of(now, zone)

        // Touching either edge counts (the SQL is `stop_ms >= from AND start_ms <= to`).
        assertThat(window.overlaps(window.fromMs - 3_600_000L, window.fromMs)).isTrue()
        assertThat(window.overlaps(window.toMs, window.toMs + 3_600_000L)).isTrue()
        assertThat(window.overlaps(window.fromMs - 3_600_000L, window.fromMs - 1L)).isFalse()
        assertThat(window.overlaps(window.toMs + 1L, window.toMs + 3_600_000L)).isFalse()
    }

    @Test
    fun `flooring is local, so a half-hour offset zone aligns to its own wall clock`() {
        val kathmandu = TimeZone.getTimeZone("Asia/Kathmandu") // UTC+05:45
        val at = Calendar.getInstance(kathmandu).apply {
            clear()
            set(2026, 5, 15, 10, 45, 30)
        }.timeInMillis

        val floored = EpgGridWindow.floorToStep(at, 30, kathmandu)
        val local = Calendar.getInstance(kathmandu).apply { timeInMillis = floored }
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = floored }

        // 10:45:30 local floors to 10:30 local — which is 04:45 UTC, not the 04:30 a UTC-based floor
        // would have produced. The ruler is the wall clock, so the coverage window follows the zone.
        assertThat(local.get(Calendar.HOUR_OF_DAY)).isEqualTo(10)
        assertThat(local.get(Calendar.MINUTE)).isEqualTo(30)
        assertThat(utc.get(Calendar.MINUTE)).isEqualTo(45)
    }

    @Test
    fun `a stored guide is empty when nothing is bound or every binding is blank`() {
        // No catalogue yet: not "empty", it is "nothing to ask about" — the gate waits instead.
        assertThat(EpgStoredGuide(channels = 0, matched = 0, programmed = 0).catalogReady).isFalse()
        assertThat(EpgStoredGuide(channels = 0, matched = 0, programmed = 0).empty).isFalse()
        // The BUG-016 white run: 571 channels, nothing bound.
        assertThat(EpgStoredGuide(channels = 571, matched = 0, programmed = 0).empty).isTrue()
        // The other shape: bound, but every binding blank.
        assertThat(EpgStoredGuide(channels = 571, matched = 140, programmed = 0).empty).isTrue()
        // Healthy: a binding with a programme suppresses the shorter retry.
        assertThat(EpgStoredGuide(channels = 571, matched = 140, programmed = 107).empty).isFalse()
    }

    private fun epochAt(hour: Int, minute: Int): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(2026, 5, 15, hour, minute, 0)
        }.timeInMillis
}
