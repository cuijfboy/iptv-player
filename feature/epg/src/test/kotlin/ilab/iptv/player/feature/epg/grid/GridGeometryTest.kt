package ilab.iptv.player.feature.epg.grid

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GridGeometryTest {

    private val metrics = tvMetrics()
    private val window = TimeWindow(1_000_000L, 1_000_000L + 6 * HOUR_MS)

    private fun geometry(scroll: ScrollOffset = ScrollOffset.ZERO, rowCount: Int = 658) = GridGeometry(
        metrics = metrics,
        viewportWidthPx = TV_WIDTH_PX,
        viewportHeightPx = TV_HEIGHT_PX,
        contentWindow = window,
        rowCount = rowCount,
        scroll = scroll,
    )

    @Test
    fun `time to pixel and back is a round trip`() {
        val geometry = geometry()
        val instants = listOf(window.fromMs, window.fromMs + 37 * MINUTE_MS, window.toMs - MINUTE_MS)
        for (instant in instants) {
            val x = geometry.xForTime(instant)
            // Rounding is to the millisecond, so the pixel round trip is exact to well under a pixel.
            assertThat(geometry.timeForX(x)).isEqualTo(instant)
        }
    }

    @Test
    fun `pixel scale matches the frozen metrics`() {
        val geometry = geometry()
        // The channel column is the axis origin; one hour is pxPerMinute * 60.
        assertThat(geometry.xForTime(window.fromMs)).isWithin(0.01f).of(metrics.channelColumnWidthPx)
        val oneHour = geometry.xForTime(window.fromMs + HOUR_MS) - geometry.xForTime(window.fromMs)
        assertThat(oneHour).isWithin(0.01f).of(metrics.pxPerMinute * 60f)
    }

    @Test
    fun `content height is header plus every row`() {
        val geometry = geometry(rowCount = 10)
        assertThat(geometry.contentHeightPx)
            .isWithin(0.01f)
            .of(metrics.headerHeightPx + 10 * metrics.rowHeightPx)
    }

    @Test
    fun `scroll is clamped to the content`() {
        val geometry = geometry(scroll = ScrollOffset(Int.MAX_VALUE, Int.MAX_VALUE))
        assertThat(geometry.offsetXPx).isEqualTo(geometry.maxScrollXPx)
        assertThat(geometry.offsetYPx).isEqualTo(geometry.maxScrollYPx)
        val negative = geometry(ScrollOffset(-500, -500))
        assertThat(negative.offsetXPx).isEqualTo(0)
        assertThat(negative.offsetYPx).isEqualTo(0)
    }

    @Test
    fun `visible rows start at the scroll position and end at the viewport bottom`() {
        // Scroll exactly one row down: the first visible row is row 1 and the header hides row 0.
        val geometry = geometry(scroll = ScrollOffset(0, metrics.rowHeightPx.toInt()))
        val rows = geometry.visibleRows()
        assertThat(rows.first).isEqualTo(1)
        // 112 px of scroll + 1080 px of viewport - 80 px of header, over a 112 px row.
        assertThat(rows.last).isEqualTo(9)
    }

    @Test
    fun `visible rows are clipped at both ends of the list`() {
        val atTop = geometry(scroll = ScrollOffset(0, 0), rowCount = 3)
        assertThat(atTop.visibleRows()).isEqualTo(0..2)
        // Three rows are shorter than the viewport, so there is nothing to scroll and every row is visible.
        val shortList = geometry(scroll = ScrollOffset(0, Int.MAX_VALUE), rowCount = 3)
        assertThat(shortList.maxScrollYPx).isEqualTo(0)
        assertThat(shortList.visibleRows()).isEqualTo(0..2)
        // A list taller than the viewport bottoms out on the last row.
        val atBottom = geometry(scroll = ScrollOffset(0, Int.MAX_VALUE), rowCount = 40)
        assertThat(atBottom.visibleRows()).isEqualTo(31..39)
        val empty = geometry(rowCount = 0)
        assertThat(empty.visibleRows().isEmpty()).isTrue()
    }

    @Test
    fun `visible time window is the pane, not the whole content`() {
        val geometry = geometry()
        val visible = geometry.visibleTimeWindow()
        // offsetX is 0, so the pane starts exactly at the window's start and is narrower than the window.
        assertThat(visible.fromMs).isEqualTo(window.fromMs)
        val paneMinutes = (TV_WIDTH_PX - metrics.channelColumnWidthPx) / metrics.pxPerMinute
        assertThat((visible.toMs - visible.fromMs) / MINUTE_MS).isEqualTo(paneMinutes.toLong())
        assertThat(visible.toMs).isLessThan(window.toMs)
    }

    @Test
    fun `prefetch widens the visible window on both sides`() {
        val geometry = geometry()
        val plain = geometry.visibleTimeWindow()
        val prefetched = geometry.visibleTimeWindow(prefetchMs = 15 * MINUTE_MS)
        assertThat(plain.fromMs - prefetched.fromMs).isEqualTo(15 * MINUTE_MS)
        assertThat(prefetched.toMs - plain.toMs).isEqualTo(15 * MINUTE_MS)
    }

    @Test
    fun `time visibility follows the scrolled pane`() {
        val geometry = geometry(scroll = ScrollOffset(2 * 60 * metrics.pxPerMinute.toInt(), 0))
        // "fromMs" has scrolled two hours off the left edge of the pane.
        assertThat(geometry.isTimeVisible(window.fromMs)).isFalse()
        assertThat(geometry.isTimeVisible(window.fromMs + 3 * HOUR_MS)).isTrue()
    }
}
