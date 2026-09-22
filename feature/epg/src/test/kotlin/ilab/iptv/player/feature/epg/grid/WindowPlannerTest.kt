package ilab.iptv.player.feature.epg.grid

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.model.EpgGridWindow
import java.util.Calendar
import java.util.TimeZone
import org.junit.Test

class WindowPlannerTest {

    private val zone = TimeZone.getTimeZone("Asia/Shanghai")
    private val axis = TimeAxis(zone)
    private val now: Long = Calendar.getInstance(zone).apply {
        clear()
        set(2026, 5, 15, 10, 7, 30)
    }.timeInMillis

    @Test
    fun `the opening window is step-aligned and keeps half an hour of history`() {
        val window = WindowPlanner.initialWindow(now, axis)
        assertThat(axis.label(window.fromMs)).isEqualTo("09:30")
        assertThat(window.spanMs).isEqualTo(WindowPlanner.DEFAULT_SPAN_MS)
        assertThat(window.contains(now)).isTrue()
    }

    @Test
    fun `the opening window and the ruler come from the one shared definition`() {
        // BUG-20260922-018: the coverage report, the binding pick and the gate all ask "what does the
        // grid show?" from `:core:model`, and the grid must answer with exactly that window — not with a
        // second copy of the span. This is the link that makes the alignment claim true.
        val window = WindowPlanner.initialWindow(now, axis)
        val shared = EpgGridWindow.of(now, zone)

        assertThat(window.fromMs).isEqualTo(shared.fromMs)
        assertThat(window.toMs).isEqualTo(shared.toMs)
        assertThat(WindowPlanner.DEFAULT_SPAN_MS).isEqualTo(EpgGridWindow.SPAN_MS)
        assertThat(WindowPlanner.DEFAULT_HISTORY_MS).isEqualTo(EpgGridWindow.HISTORY_MS)
        assertThat(axis.floorToStep(now))
            .isEqualTo(EpgGridWindow.floorToStep(now, EpgGridWindow.RULER_STEP_MINUTES, zone))
    }

    @Test
    fun `a cursor in the middle leaves the window alone`() {
        val window = WindowPlanner.initialWindow(now, axis)
        val same = WindowPlanner.ensureCursorVisible(now + 2 * HOUR_MS, window)
        assertThat(same).isEqualTo(window)
    }

    @Test
    fun `reaching the left edge extends the window backwards`() {
        val window = WindowPlanner.initialWindow(now, axis)
        val cursor = window.fromMs + 5 * MINUTE_MS
        val extended = WindowPlanner.ensureCursorVisible(cursor, window)
        assertThat(extended.fromMs).isLessThan(window.fromMs)
        assertThat(extended.toMs).isEqualTo(window.toMs)
        assertThat(cursor - extended.fromMs).isAtLeast(WindowPlanner.EDGE_MARGIN_MS)
    }

    @Test
    fun `reaching the right edge extends the window forwards`() {
        val window = WindowPlanner.initialWindow(now, axis)
        val cursor = window.toMs - 5 * MINUTE_MS
        val extended = WindowPlanner.ensureCursorVisible(cursor, window)
        assertThat(extended.toMs).isGreaterThan(window.toMs)
        assertThat(extended.fromMs).isEqualTo(window.fromMs)
        assertThat(extended.toMs - cursor).isAtLeast(WindowPlanner.EDGE_MARGIN_MS)
    }

    @Test
    fun `a window that is already at the cap gives up ground on the far side`() {
        val max = TimeWindow.MAX_SPAN_MS
        val window = TimeWindow(now - max / 2, now + max / 2)
        val extended = WindowPlanner.ensureCursorVisible(window.toMs - MINUTE_MS, window)
        assertThat(extended.spanMs).isAtMost(max)
        assertThat(extended.contains(window.toMs - MINUTE_MS)).isTrue()
        assertThat(extended.fromMs).isGreaterThan(window.fromMs)
    }

    @Test
    fun `the first page covers the first viewport with prefetch`() {
        val channels = (1L..658L).toList()
        val page = WindowPlanner.channelPage(firstVisibleRow = 0, lastVisibleRow = 8, channelIds = channels)
        assertThat(page).hasSize(32)
        assertThat(page).isEqualTo(channels.subList(0, 32))
    }

    @Test
    fun `a viewport straddling a page boundary loads exactly two pages`() {
        val channels = (1L..658L).toList()
        val page = WindowPlanner.channelPage(firstVisibleRow = 30, lastVisibleRow = 40, channelIds = channels)
        assertThat(page).hasSize(64)
        assertThat(page).isEqualTo(channels.subList(0, 64))
    }

    @Test
    fun `scrolling one row inside a page does not change the query`() {
        val channels = (1L..658L).toList()
        val first = WindowPlanner.channelPage(100, 110, channels)
        val second = WindowPlanner.channelPage(101, 111, channels)
        assertThat(second).isEqualTo(first)
        assertThat(first.first()).isEqualTo(65L)
        assertThat(first.last()).isEqualTo(128L)
    }

    @Test
    fun `the page never runs past the end of the channel table`() {
        val channels = (1L..20L).toList()
        val page = WindowPlanner.channelPage(18, 19, channels)
        assertThat(page).isEqualTo(channels)
    }

    @Test
    fun `no channels means no query`() {
        assertThat(WindowPlanner.channelPage(0, 5, emptyList())).isEmpty()
    }
}
