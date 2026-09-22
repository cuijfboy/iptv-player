package ilab.iptv.player.feature.epg.grid

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * The EPG time grid's geometry (docs/02 §8.3). Pure Kotlin on purpose: the time↔pixel mapping and the
 * visible-rectangle arithmetic are the two things that silently break at the edges (DST, the last row,
 * an empty window), and they are testable on the JVM with no Android in the way.
 *
 * Layout, frozen by §8.3:
 *
 * ```
 *  (0,0) ┌─────────────────────────── header (sticky, scrolls horizontally) ─────────────┐
 *        ├──────────────┬───────────────────────────────────────────────────────────────┤
 *        │ channel      │  rows ×  time axis (scrolls both ways)                        │
 *        │ column       │                                                               │
 *        │ (sticky,     │                                                               │
 *        │  vertical)   │                                                               │
 * ```
 *
 * The horizontal axis is **linear in real time** (epoch milliseconds), so a programme's width is its
 * real duration. That is what makes daylight-saving transitions honest: XMLTV timestamps carry an
 * explicit UTC offset (`XmltvTime`), so a 30-minute programme around a spring-forward stays 30 minutes
 * wide while the wall-clock labels on the ruler skip the hour that does not exist.
 */

/** Half-open time window `[fromMs, toMs)` — the shape every EPG query and every frame uses. */
data class TimeWindow(val fromMs: Long, val toMs: Long) {
    val spanMs: Long get() = toMs - fromMs
    val isEmpty: Boolean get() = toMs <= fromMs

    fun contains(ms: Long): Boolean = ms >= fromMs && ms < toMs

    /** Clamps into the window, never below [fromMs] even for an empty/degenerate window. */
    fun clamp(ms: Long): Long = if (toMs <= fromMs) fromMs else ms.coerceIn(fromMs, toMs - 1)

    companion object {
        /** The docs/02 §8.3 window cap: one grid page is ≤24 h. */
        const val MAX_SPAN_MS: Long = 24L * 60L * 60L * 1000L
    }
}

/** The scroll position, already clamped to the content by [GridGeometry]. */
data class ScrollOffset(val xPx: Int, val yPx: Int) {
    companion object {
        val ZERO = ScrollOffset(0, 0)
    }
}

/**
 * Sizes in **pixels** (density multiplied by the caller). Defaults come from the S3 prototype
 * (`docs/05-过程记录/07-Spike报告.md` §4), with one deliberate change: the row is 56 dp instead of the
 * prototype's 40 dp, because docs/02 §8.2 freezes a 48 dp minimum focus target and a grid row *is* the
 * focus target. The report records the resulting block counts, which is the number §8.3 is keyed on.
 */
data class GridMetrics(
    val rowHeightPx: Float,
    val headerHeightPx: Float,
    val channelColumnWidthPx: Float,
    val pxPerMinute: Float,
    val blockGapPx: Float,
    val blockPaddingPx: Float,
    /** Below this the renderer draws the block without text instead of a one-glyph smear. */
    val minTextWidthPx: Float,
) {
    companion object {
        fun fromDensity(density: Float): GridMetrics = GridMetrics(
            rowHeightPx = 56f * density,
            headerHeightPx = 40f * density,
            channelColumnWidthPx = 160f * density,
            pxPerMinute = 3.2f * density,
            blockGapPx = 2f * density,
            blockPaddingPx = 8f * density,
            minTextWidthPx = 48f * density,
        )
    }
}

/**
 * The pure geometry of one frame: content size, scroll clamping, time↔pixel mapping and the two
 * visible ranges (rows, time). Everything the frame builder and the focus navigator need.
 */
class GridGeometry(
    val metrics: GridMetrics,
    val viewportWidthPx: Int,
    val viewportHeightPx: Int,
    val contentWindow: TimeWindow,
    val rowCount: Int,
    scroll: ScrollOffset,
) {
    val contentWidthPx: Float =
        metrics.channelColumnWidthPx + minutesBetween(contentWindow.fromMs, contentWindow.toMs) * metrics.pxPerMinute

    val contentHeightPx: Float = metrics.headerHeightPx + max(0, rowCount) * metrics.rowHeightPx

    val maxScrollXPx: Int = max(0, ceil(contentWidthPx - viewportWidthPx).toInt())
    val maxScrollYPx: Int = max(0, ceil(contentHeightPx - viewportHeightPx).toInt())

    /** The clamped scroll the frame is actually drawn at. */
    val scroll: ScrollOffset = ScrollOffset(scroll.xPx.coerceIn(0, maxScrollXPx), scroll.yPx.coerceIn(0, maxScrollYPx))
    val offsetXPx: Int get() = scroll.xPx
    val offsetYPx: Int get() = scroll.yPx

    /** Content x of an instant. Monotonic and linear in real time (see the class note on DST). */
    fun xForTime(atMs: Long): Float =
        metrics.channelColumnWidthPx + minutesBetween(contentWindow.fromMs, atMs) * metrics.pxPerMinute

    /** Instant at a content x. Rounded, because Sub-pixel precision is meaningless at minute scale. */
    fun timeForX(xPx: Float): Long {
        val minutes = (xPx - metrics.channelColumnWidthPx) / metrics.pxPerMinute
        return contentWindow.fromMs + (minutes * 60_000.0).roundToLong()
    }

    fun yForRow(rowIndex: Int): Float = metrics.headerHeightPx + rowIndex * metrics.rowHeightPx

    /** Row containing a content y; may be out of range for the header strip or past the end. */
    fun rowForY(yPx: Float): Int = floor((yPx - metrics.headerHeightPx) / metrics.rowHeightPx).toInt()

    /**
     * The rows whose rectangles intersect the visible band, before the prefetch margin. The band
     * starts at `offsetY + headerHeight` because the header is sticky over the top of the content.
     */
    fun visibleRows(): IntRange {
        if (rowCount <= 0) return IntRange.EMPTY
        val first = max(0, floor(offsetYPx.toFloat() / metrics.rowHeightPx).toInt())
        val last = floor((offsetYPx + viewportHeightPx - metrics.headerHeightPx) / metrics.rowHeightPx).toInt()
        return first.coerceAtMost(rowCount - 1)..last.coerceIn(0, rowCount - 1)
    }

    /**
     * The window the frame builder should materialise: the visible band expanded by the prefetch
     * margin. The time band starts at the channel column's right edge, because that column is sticky.
     */
    fun visibleTimeWindow(prefetchMs: Long = 0L): TimeWindow = TimeWindow(
        fromMs = timeForX((offsetXPx + metrics.channelColumnWidthPx).toFloat()) - prefetchMs,
        toMs = timeForX((offsetXPx + viewportWidthPx).toFloat()) + prefetchMs,
    )

    /** True when the cursor instant is inside the visible time band (the "is the selection on screen" test). */
    fun isTimeVisible(atMs: Long): Boolean {
        val x = xForTime(atMs) - offsetXPx
        return x >= metrics.channelColumnWidthPx && x <= viewportWidthPx
    }

    fun isRowVisible(rowIndex: Int): Boolean {
        val top = yForRow(rowIndex) - offsetYPx
        return top >= metrics.headerHeightPx && top + metrics.rowHeightPx <= viewportHeightPx
    }

    private fun minutesBetween(fromMs: Long, toMs: Long): Float = (toMs - fromMs) / 60_000f
}
