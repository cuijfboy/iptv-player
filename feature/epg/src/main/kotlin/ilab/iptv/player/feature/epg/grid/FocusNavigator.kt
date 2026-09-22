package ilab.iptv.player.feature.epg.grid

import kotlin.math.max

/**
 * The grid's remote-control model (docs/02 §8.2 焦点路由表: "网格页上下键跨行取同列节目，左右键按时间步进").
 *
 * Pure arithmetic on purpose: every bug in this code path is "one row off at the end of the list" or "the
 * cursor walked off the left edge and stayed there", and neither is testable by eye.
 *
 * - LEFT / RIGHT move the **cursor instant** by one ruler step and re-resolve the programme under it, in
 *   the same row. Stepping rather than jumping to the next programme is what the focus table freezes.
 * - UP / DOWN change the **row** and keep the instant, so the cursor stays on the same clock time — the
 *   property that makes a grid feel like a grid.
 * - [ensureVisible] scrolls the minimum amount that puts the cursor back inside the safe area, so the view
 *   needs no smooth-scroll per key press.
 */
class FocusNavigator(private val timeAxis: TimeAxis) {

    /** A fresh grid opens on the current programme of the first row, or on "now" when there is none. */
    fun initial(rowIndex: Int, window: TimeWindow, row: GridRowInput?, nowMs: Long): GridSelection {
        val time = if (window.contains(nowMs)) nowMs else window.clamp(nowMs)
        return GridSelection.of(rowIndex, time, row)
    }

    fun moveLeft(current: GridSelection, rowCount: Int, window: TimeWindow, rowAt: (Int) -> GridRowInput?): GridSelection =
        moveSteps(current, -1, rowCount, window, rowAt)

    fun moveRight(current: GridSelection, rowCount: Int, window: TimeWindow, rowAt: (Int) -> GridRowInput?): GridSelection =
        moveSteps(current, +1, rowCount, window, rowAt)

    fun moveUp(current: GridSelection, rowCount: Int, rowAt: (Int) -> GridRowInput?): GridSelection =
        moveRow(current, -1, rowCount, rowAt)

    fun moveDown(current: GridSelection, rowCount: Int, rowAt: (Int) -> GridRowInput?): GridSelection =
        moveRow(current, +1, rowCount, rowAt)

    /**
     * The minimum scroll that puts [current] inside the safe area. The horizontal safe area is a sixth of
     * the time pane at each side, so the cursor keeps moving while the grid stands still and the view only
     * pages over when the cursor would otherwise reach the edge.
     */
    fun ensureVisible(
        current: GridSelection,
        metrics: GridMetrics,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
        scroll: ScrollOffset,
        window: TimeWindow,
        rowCount: Int,
        verticalMarginPx: Float = 0f,
    ): ScrollOffset {
        val geometry = GridGeometry(metrics, viewportWidthPx, viewportHeightPx, window, rowCount, scroll)
        var x = geometry.offsetXPx
        var y = geometry.offsetYPx

        if (rowCount > 0) {
            val row = current.rowIndex.coerceIn(0, rowCount - 1)
            val rowTop = geometry.yForRow(row)
            val rowBottom = rowTop + metrics.rowHeightPx
            val visibleTop = y + metrics.headerHeightPx
            val visibleBottom = y + viewportHeightPx
            if (rowTop < visibleTop + verticalMarginPx) {
                y = (rowTop - metrics.headerHeightPx - verticalMarginPx).toInt()
            }
            if (rowBottom > visibleBottom - verticalMarginPx) {
                y = (rowBottom - viewportHeightPx + verticalMarginPx).toInt()
            }
        }

        val paneWidthPx = viewportWidthPx - metrics.channelColumnWidthPx
        val marginPx = max(0f, paneWidthPx / 6f)
        val cursorX = geometry.xForTime(current.timeMs)
        if (cursorX < x + metrics.channelColumnWidthPx + marginPx) {
            x = (cursorX - metrics.channelColumnWidthPx - marginPx).toInt()
        }
        if (cursorX > x + viewportWidthPx - marginPx) {
            x = (cursorX - viewportWidthPx + marginPx).toInt()
        }

        return GridGeometry(metrics, viewportWidthPx, viewportHeightPx, window, rowCount, ScrollOffset(x, y)).scroll
    }

    private fun moveSteps(
        current: GridSelection,
        steps: Int,
        rowCount: Int,
        window: TimeWindow,
        rowAt: (Int) -> GridRowInput?,
    ): GridSelection {
        if (rowCount <= 0) return current
        val row = current.rowIndex.coerceIn(0, rowCount - 1)
        val time = window.clamp(timeAxis.addSteps(current.timeMs, steps))
        return GridSelection.of(row, time, rowAt(row))
    }

    private fun moveRow(
        current: GridSelection,
        delta: Int,
        rowCount: Int,
        rowAt: (Int) -> GridRowInput?,
    ): GridSelection {
        if (rowCount <= 0) return current
        val row = (current.rowIndex + delta).coerceIn(0, rowCount - 1)
        return GridSelection.of(row, current.timeMs, rowAt(row))
    }
}
