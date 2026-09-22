package ilab.iptv.player.feature.epg.grid

import ilab.iptv.player.core.model.EpgGridWindow

/**
 * Decides **which** time window and **which** channels the grid asks `EpgRepository` for.
 *
 * Two frozen constraints shape this (docs/02 §8.3): the query is capped at ≤64 channels and ≤24 h, so the
 * grid can never ask for the whole guide; and the grid must not load all 658 channels either — it asks
 * only for the rows near the viewport.
 *
 * The channel half is **page-aligned** ([PAGE_CHANNELS]): scrolling inside a page changes nothing, and a
 * page is only re-queried when the viewport crosses a page boundary. That keeps a slow scroll from firing
 * a database query per frame, which is what "窗口边界要能平滑翻页" means in practice.
 */
object WindowPlanner {

    /**
     * Opens on six hours, the window the S3 prototype measured (docs/05 07 §4).
     *
     * **Both numbers are [EpgGridWindow]'s, not this file's** (BUG-20260922-018). The coverage report,
     * the binding pick and the "is this guide empty?" gate all ask the same question — "what does the
     * grid show?" — and they live in modules that cannot see `:feature:epg`, so the definition sits in
     * `:core:model` and is consumed from both sides. Nothing here may grow a second copy of the span.
     */
    const val DEFAULT_SPAN_MS: Long = EpgGridWindow.SPAN_MS

    /** Where "now" sits inside the opening window: a little history left, most of the day to the right. */
    const val DEFAULT_HISTORY_MS: Long = EpgGridWindow.HISTORY_MS

    /** The cursor may get this close to either edge before the window is extended. */
    const val EDGE_MARGIN_MS: Long = 60L * 60L * 1000L

    /** How much is added when an edge is reached. */
    const val EXTEND_MS: Long = 3L * 60L * 60L * 1000L

    /** Channels per EPG page: two pages cover the frozen 64-channel cap with prefetch room. */
    const val PAGE_CHANNELS: Int = 32

    /** Rows loaded beyond the visible band, so a fast scroll does not flash "loading". */
    const val PREFETCH_ROWS: Int = 8

    /**
     * The opening window: aligned to a ruler step so the leftmost tick is a real tick. It is
     * [EpgGridWindow.of] verbatim — the shared definition of "the window the grid shows" — mapped onto
     * the grid's own `TimeWindow` type.
     */
    fun initialWindow(nowMs: Long, timeAxis: TimeAxis): TimeWindow {
        val window = EpgGridWindow.of(nowMs, timeAxis.zone)
        return TimeWindow(window.fromMs, window.toMs)
    }

    /**
     * Grows the window so the cursor keeps [EDGE_MARGIN_MS] of headroom on both sides. The span is held
     * inside the §8.3 24 h cap by pushing the opposite edge, never by dropping the cursor.
     */
    fun ensureCursorVisible(
        cursorMs: Long,
        window: TimeWindow,
        marginMs: Long = EDGE_MARGIN_MS,
        extendMs: Long = EXTEND_MS,
    ): TimeWindow {
        var from = window.fromMs
        var to = window.toMs
        if (cursorMs - from < marginMs) {
            from = cursorMs - marginMs - extendMs
            if (to - from > TimeWindow.MAX_SPAN_MS) to = from + TimeWindow.MAX_SPAN_MS
        } else if (to - cursorMs < marginMs) {
            to = cursorMs + marginMs + extendMs
            if (to - from > TimeWindow.MAX_SPAN_MS) from = to - TimeWindow.MAX_SPAN_MS
        }
        return TimeWindow(from, to)
    }

    /**
     * The channel ids of the page covering `[firstVisibleRow, lastVisibleRow]` plus the prefetch margin.
     * Empty when there are no channels; never wider than two pages, because it snaps to page boundaries.
     */
    fun channelPage(
        firstVisibleRow: Int,
        lastVisibleRow: Int,
        channelIds: List<Long>,
        prefetchRows: Int = PREFETCH_ROWS,
        pageSize: Int = PAGE_CHANNELS,
    ): List<Long> {
        val rowCount = channelIds.size
        if (rowCount == 0) return emptyList()
        val first = (firstVisibleRow - prefetchRows).coerceIn(0, rowCount - 1)
        val last = (lastVisibleRow + prefetchRows).coerceIn(0, rowCount - 1)
        val pageFirst = (first / pageSize) * pageSize
        val pageLast = ((last / pageSize) + 1) * pageSize - 1
        return (pageFirst..pageLast.coerceAtMost(rowCount - 1)).map { channelIds[it] }
    }
}
