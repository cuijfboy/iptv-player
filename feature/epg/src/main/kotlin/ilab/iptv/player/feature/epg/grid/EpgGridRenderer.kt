package ilab.iptv.player.feature.epg.grid

import kotlin.math.max

/**
 * The strings the grid draws. Injected so the pure layer holds no user-visible text of its own; the
 * activity supplies them from resources and the tests take the defaults.
 */
data class GridStrings(
    val noEpg: String = "无节目单",
    val loading: String = "载入中…",
    val nowLabel: String = "现在",
)

/**
 * Paints one [GridFrame] onto a [DrawSurface] (docs/02 §8.3: Canvas 自绘, 频道列固定, 时间轴吸顶,
 * 当前时间红线, 选中态与焦点态).
 *
 * The renderer is pure Kotlin and takes an already-virtualised frame: it draws exactly the blocks the
 * builder materialised, in screen pixels, in four layers so the sticky chrome wins:
 *
 * 1. rows (blocks, placeholders, the now line) — the only layer that scales with the data;
 * 2. the channel column, drawn after the rows so a block scrolled under it is covered, not clipped;
 * 3. the ruler, drawn after the rows for the same reason vertically;
 * 4. the corner cell and the bottom hairline.
 *
 * Focus feedback follows §8.2's three-way rule: the focused row gets a lifted fill, the selected block
 * gets a bright fill *and* a thick stroke, so a remote user can see both "which channel" and "which
 * programme" at a glance.
 */
class EpgGridRenderer(
    private val timeAxis: TimeAxis,
    private val palette: GridPalette = GridPalette(),
    private val strings: GridStrings = GridStrings(),
) {

    fun render(surface: DrawSurface, text: TextLayoutStore, frame: GridFrame) {
        val geometry = frame.geometry
        val metrics = geometry.metrics
        val width = surface.widthPx.toFloat()
        val height = surface.heightPx.toFloat()
        val offsetX = geometry.offsetXPx.toFloat()
        val offsetY = geometry.offsetYPx.toFloat()
        val columnWidth = metrics.channelColumnWidthPx
        val headerHeight = metrics.headerHeightPx
        val rowHeight = metrics.rowHeightPx
        val hairline = max(1f, metrics.blockGapPx / 2f)

        surface.fillRect(0f, 0f, width, height, palette.background)

        // ---- 1. rows, blocks, placeholders ----
        for (row in frame.rows) {
            val top = row.topPx - offsetY
            val bottom = top + rowHeight
            if (bottom <= headerHeight || top >= height) continue
            val rowFill = when {
                row.focused -> palette.focusedRowFill
                row.rowIndex % 2 == 0 -> palette.rowEven
                else -> palette.rowOdd
            }
            surface.fillRect(0f, top, width, bottom, rowFill)
            surface.drawLine(0f, bottom, width, bottom, palette.rowLine, hairline)

            for (block in row.blocks) {
                val left = block.leftPx - offsetX
                val right = block.rightPx - offsetX
                if (right <= columnWidth || left >= width) continue
                drawBlock(surface, text, frame, block, left, right, top, bottom, columnWidth, width)
            }
            for (placeholder in row.placeholders) {
                val left = placeholder.leftPx - offsetX
                val right = placeholder.rightPx - offsetX
                if (right <= columnWidth || left >= width) continue
                drawPlaceholder(surface, text, placeholder, left, right, top, bottom)
            }
        }

        frame.nowMs?.let { now ->
            val x = geometry.xForTime(now) - offsetX
            if (x in columnWidth..width) {
                surface.drawLine(x, headerHeight, x, height, palette.nowLine, 2f)
                val handle = text.get(strings.nowLabel, NOW_LABEL_WIDTH_PX, GridTextStyle.RULER)
                surface.drawText(handle, x + 3f, headerHeight + 3f)
            }
        }

        // ---- 2. channel column (sticky left) ----
        surface.fillRect(0f, headerHeight, columnWidth, height, palette.channelColumnBackground)
        for (row in frame.rows) {
            val top = row.topPx - offsetY
            val bottom = top + rowHeight
            if (bottom <= headerHeight || top >= height) continue
            if (row.focused) {
                surface.fillRect(0f, max(top, headerHeight), columnWidth, bottom, palette.focusedRowFill)
            }
            val label = channelLabel(row)
            val available = (columnWidth - 2f * metrics.blockPaddingPx).toInt()
            val handle = text.get(label, available, GridTextStyle.CHANNEL)
            val textTop = top + (rowHeight - handle.heightPx) / 2f
            surface.drawText(handle, metrics.blockPaddingPx, textTop)
            surface.drawLine(0f, bottom, columnWidth, bottom, palette.rowLine, hairline)
        }
        surface.drawLine(columnWidth, headerHeight, columnWidth, height, palette.rowLine, hairline)

        // ---- 3. ruler (sticky top) ----
        surface.fillRect(columnWidth, 0f, width, headerHeight, palette.headerBackground)
        for (tick in frame.ticks) {
            val x = geometry.xForTime(tick.atMs) - offsetX
            if (x < columnWidth || x > width) continue
            val color = if (tick.isDayBoundary) palette.dayBoundaryLine else palette.tickLine
            surface.drawLine(x, 0f, x, headerHeight, color, if (tick.isDayBoundary) 2f else hairline)
            if (tick.isDayBoundary) {
                val day = text.get(timeAxis.dayLabel(tick.atMs), DAY_LABEL_WIDTH_PX, GridTextStyle.RULER_DAY)
                surface.drawText(day, x + 4f, 3f)
            }
            val label = text.get(tick.label, TICK_LABEL_WIDTH_PX, GridTextStyle.RULER)
            surface.drawText(label, x + 4f, headerHeight - label.heightPx - 3f)
        }

        // ---- 4. corner + bottom hairline ----
        surface.fillRect(0f, 0f, columnWidth, headerHeight, palette.headerBackground)
        val corner = text.get(frame.cornerLabel, CORNER_LABEL_WIDTH_PX, GridTextStyle.RULER_DAY)
        surface.drawText(corner, metrics.blockPaddingPx, (headerHeight - corner.heightPx) / 2f)
        surface.drawLine(0f, headerHeight, width, headerHeight, palette.rowLine, hairline)
    }

    private fun drawBlock(
        surface: DrawSurface,
        text: TextLayoutStore,
        frame: GridFrame,
        block: GridBlock,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
        paneLeft: Float,
        paneRight: Float,
    ) {
        val metrics = frame.geometry.metrics
        val gap = metrics.blockGapPx
        val insetLeft = left + gap
        val insetRight = right - gap
        if (insetRight - insetLeft <= 0f) return

        val fill = if (block.selected) {
            palette.blockSelectedFill
        } else if (block.programmeId % 2L == 0L) {
            palette.blockFill
        } else {
            palette.blockFillAlt
        }
        surface.fillRect(insetLeft, top + gap, insetRight, bottom - gap, fill)
        surface.strokeRect(
            insetLeft,
            top + gap,
            insetRight,
            bottom - gap,
            if (block.selected) palette.blockSelectedStroke else palette.blockStroke,
            if (block.selected) SELECTED_STROKE_PX else 1f,
        )

        // Text is clipped to the time pane, not to the block: a block half-scrolled under the sticky
        // channel column must not paint its title over that column.
        val textLeft = maxOf(insetLeft, paneLeft)
        val textRight = minOf(insetRight, paneRight)
        val available = (textRight - textLeft - 2f * metrics.blockPaddingPx).toInt()
        if (available < metrics.minTextWidthPx) return
        val padding = metrics.blockPaddingPx
        val titleHandle = text.get(block.title, available, GridTextStyle.BLOCK_TITLE)
        val titleTop = top + gap + padding / 2f
        surface.drawText(titleHandle, textLeft + padding, titleTop)
        val timeTop = titleTop + titleHandle.heightPx
        if (timeTop + padding <= bottom - gap) {
            val timeHandle = text.get(block.timeLabel, available, GridTextStyle.BLOCK_TIME)
            surface.drawText(timeHandle, textLeft + padding, timeTop)
        }
    }

    private fun drawPlaceholder(
        surface: DrawSurface,
        text: TextLayoutStore,
        placeholder: GridPlaceholder,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
    ) {
        surface.fillRect(left + 1f, top + 1f, right - 1f, bottom - 1f, palette.placeholderFill)
        surface.strokeRect(left + 1f, top + 1f, right - 1f, bottom - 1f, palette.placeholderStroke, 1f)
        val label = when (placeholder.kind) {
            PlaceholderKind.NO_EPG -> strings.noEpg
            PlaceholderKind.LOADING -> strings.loading
            PlaceholderKind.GAP -> return
        }
        val available = (right - left - 24f).toInt()
        if (available < MIN_PLACEHOLDER_LABEL_PX) return
        val handle = text.get(label, available, GridTextStyle.PLACEHOLDER)
        surface.drawText(handle, left + 12f, top + (bottom - top - handle.heightPx) / 2f)
    }

    private fun channelLabel(row: GridRowFrame): String {
        val number = row.channelNo
        return if (number == null) row.name else "$number ${row.name}"
    }

    private companion object {
        const val SELECTED_STROKE_PX = 3f
        const val NOW_LABEL_WIDTH_PX = 48
        const val TICK_LABEL_WIDTH_PX = 56
        const val DAY_LABEL_WIDTH_PX = 64
        const val CORNER_LABEL_WIDTH_PX = 120
        const val MIN_PLACEHOLDER_LABEL_PX = 72
    }
}
