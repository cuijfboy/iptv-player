package ilab.iptv.player.feature.epg.grid

/**
 * The drawing verbs the grid renderer needs — deliberately the smallest set, so the renderer is pure
 * Kotlin and can be driven by a recording fake in unit tests and in the offline benchmark.
 *
 * Coordinates are **screen pixels**, already offset: the renderer subtracts the scroll itself instead of
 * leaning on a canvas translate. That keeps "is this inside the visible rectangle" checkable with plain
 * arithmetic, which is what the tests and the benchmark assert.
 */
interface DrawSurface {
    val widthPx: Int
    val heightPx: Int

    fun fillRect(left: Float, top: Float, right: Float, bottom: Float, color: Int)

    fun strokeRect(left: Float, top: Float, right: Float, bottom: Float, color: Int, strokeWidthPx: Float)

    fun drawLine(x0: Float, y0: Float, x1: Float, y1: Float, color: Int, strokeWidthPx: Float)

    fun drawText(handle: TextHandle, x: Float, y: Float)
}

/**
 * Colours as plain ARGB ints (no `android.graphics.Color`), so the renderer and its palette stay
 * JVM-testable. These are the grid's own dark-theme values: `:core:design` has no design tokens yet, so
 * this module keeps its palette beside its only view, the way `:feature:channels` keeps its drawables.
 */
data class GridPalette(
    val background: Int = 0xFF101014.toInt(),
    val rowEven: Int = 0xFF161B22.toInt(),
    val rowOdd: Int = 0xFF12161C.toInt(),
    val rowLine: Int = 0xFF232A33.toInt(),
    val focusedRowFill: Int = 0xFF1E2833.toInt(),
    val headerBackground: Int = 0xFF0B0E12.toInt(),
    val channelColumnBackground: Int = 0xFF0D1015.toInt(),
    val tickLine: Int = 0xFF2A313B.toInt(),
    val dayBoundaryLine: Int = 0xFF445063.toInt(),
    val blockFill: Int = 0xFF2E4A6B.toInt(),
    val blockFillAlt: Int = 0xFF3B6B4A.toInt(),
    val blockStroke: Int = 0xFF3C5A80.toInt(),
    val blockSelectedFill: Int = 0xFF3F6FA8.toInt(),
    val blockSelectedStroke: Int = 0xFFFFD166.toInt(),
    val placeholderFill: Int = 0xFF1A1F26.toInt(),
    val placeholderStroke: Int = 0xFF262D36.toInt(),
    val nowLine: Int = 0xFFD64545.toInt(),
    val textPrimary: Int = 0xFFF2F4F8.toInt(),
    val textSecondary: Int = 0xFFAEB6C4.toInt(),
    val textMuted: Int = 0xFF6C7686.toInt(),
)
