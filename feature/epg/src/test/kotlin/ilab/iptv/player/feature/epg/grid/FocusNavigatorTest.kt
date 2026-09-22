package ilab.iptv.player.feature.epg.grid

import com.google.common.truth.Truth.assertThat
import java.util.TimeZone
import org.junit.Test

class FocusNavigatorTest {

    private val metrics = tvMetrics()
    private val axis = TimeAxis(TimeZone.getTimeZone("Asia/Shanghai"))
    private val navigator = FocusNavigator(axis)
    private val window = TimeWindow(1_800_000_000_000L, 1_800_000_000_000L + 6 * HOUR_MS)
    private val rows = loadedRows(60, window.fromMs, hours = 6)

    private fun rowAt(index: Int): GridRowInput? = rows.getOrNull(index)

    @Test
    fun `the grid opens on now and resolves the programme under it`() {
        val now = window.fromMs + 75 * MINUTE_MS
        val selection = navigator.initial(0, window, rowAt(0), now)
        assertThat(selection.timeMs).isEqualTo(now)
        assertThat(selection.programmeId).isNotNull()
        assertThat(selection.programmeId).isEqualTo(rowAt(0)?.index?.at(now)?.id)
    }

    @Test
    fun `an instant outside the window is clamped into it`() {
        val selection = navigator.initial(0, window, rowAt(0), window.toMs + HOUR_MS)
        assertThat(selection.timeMs).isEqualTo(window.toMs - 1)
    }

    @Test
    fun `right moves the cursor one ruler step and re-resolves the programme`() {
        val start = window.fromMs + 10 * MINUTE_MS
        val before = GridSelection.of(0, start, rowAt(0))
        val after = navigator.moveRight(before, rows.size, window, ::rowAt)
        assertThat(after.timeMs - before.timeMs).isEqualTo(30 * MINUTE_MS)
        assertThat(after.programmeId).isEqualTo(rowAt(0)?.index?.at(after.timeMs)?.id)
    }

    @Test
    fun `left at the window start stays inside the window`() {
        val atStart = GridSelection.of(0, window.fromMs, rowAt(0))
        val moved = navigator.moveLeft(atStart, rows.size, window, ::rowAt)
        assertThat(moved.timeMs).isEqualTo(window.fromMs)
    }

    @Test
    fun `up and down change the row and keep the instant`() {
        val selection = GridSelection.of(3, window.fromMs + 45 * MINUTE_MS, rowAt(3))
        val down = navigator.moveDown(selection, rows.size, ::rowAt)
        assertThat(down.rowIndex).isEqualTo(4)
        assertThat(down.timeMs).isEqualTo(selection.timeMs)
        assertThat(down.programmeId).isEqualTo(rowAt(4)?.index?.at(down.timeMs)?.id)

        val up = navigator.moveUp(selection, rows.size, ::rowAt)
        assertThat(up.rowIndex).isEqualTo(2)
        assertThat(up.timeMs).isEqualTo(selection.timeMs)
    }

    @Test
    fun `the cursor stops at the first and last row`() {
        assertThat(navigator.moveUp(GridSelection.of(0, window.fromMs, rowAt(0)), rows.size, ::rowAt).rowIndex)
            .isEqualTo(0)
        assertThat(
            navigator.moveDown(GridSelection.of(rows.size - 1, window.fromMs, rowAt(rows.size - 1)), rows.size, ::rowAt)
                .rowIndex,
        ).isEqualTo(rows.size - 1)
    }

    @Test
    fun `a row with no guide answers a null programme instead of a stale one`() {
        val bare = rows.toMutableList()
        bare[2] = GridRowInput(3, "No guide", null, EpgRowState.NO_EPG, null)
        val selection = GridSelection.of(1, window.fromMs + 30 * MINUTE_MS, bare[1])
        val moved = navigator.moveDown(selection, bare.size) { bare.getOrNull(it) }
        assertThat(moved.rowIndex).isEqualTo(2)
        assertThat(moved.programmeId).isNull()
    }

    @Test
    fun `ensureVisible scrolls down just enough to show the row`() {
        val selection = GridSelection.of(20, window.fromMs, rowAt(20))
        val scroll = navigator.ensureVisible(selection, metrics, TV_WIDTH_PX, TV_HEIGHT_PX, ScrollOffset.ZERO, window, rows.size)
        // Row 20 spans [2320, 2432) in content pixels; the viewport shows [80, 1080) from the top.
        assertThat(scroll.yPx).isEqualTo(2432 - TV_HEIGHT_PX)
        assertThat(scroll.xPx).isEqualTo(0)
    }

    @Test
    fun `ensureVisible pages horizontally when the cursor reaches the right edge`() {
        val late = window.fromMs + 5 * HOUR_MS + 30 * MINUTE_MS
        val selection = GridSelection.of(0, late, rowAt(0))
        val scroll = navigator.ensureVisible(selection, metrics, TV_WIDTH_PX, TV_HEIGHT_PX, ScrollOffset.ZERO, window, rows.size)
        assertThat(scroll.xPx).isGreaterThan(0)
        val geometry = GridGeometry(metrics, TV_WIDTH_PX, TV_HEIGHT_PX, window, rows.size, scroll)
        assertThat(geometry.isTimeVisible(late)).isTrue()
    }

    @Test
    fun `ensureVisible never scrolls past the content`() {
        val selection = GridSelection.of(rows.size - 1, window.toMs - 1, rowAt(rows.size - 1))
        val scroll = navigator.ensureVisible(selection, metrics, TV_WIDTH_PX, TV_HEIGHT_PX, ScrollOffset.ZERO, window, rows.size)
        val geometry = GridGeometry(metrics, TV_WIDTH_PX, TV_HEIGHT_PX, window, rows.size, ScrollOffset.ZERO)
        assertThat(scroll.xPx).isAtMost(geometry.maxScrollXPx)
        assertThat(scroll.yPx).isAtMost(geometry.maxScrollYPx)
        assertThat(scroll.xPx).isAtLeast(0)
        assertThat(scroll.yPx).isAtLeast(0)
    }

    @Test
    fun `an empty channel table leaves the selection alone`() {
        val selection = GridSelection.of(0, window.fromMs, null)
        assertThat(navigator.moveDown(selection, 0, ::rowAt)).isEqualTo(selection)
        assertThat(navigator.moveRight(selection, 0, window, ::rowAt)).isEqualTo(selection)
    }
}
