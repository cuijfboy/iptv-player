package ilab.iptv.player.feature.epg.grid

import kotlin.math.max
import kotlin.math.min

/**
 * Builds one [GridFrame] from the scroll position and the row data (docs/02 §8.3).
 *
 * This is the **2D virtualisation** the freeze calls a hard requirement: rows are limited to the visible
 * band plus [GridFrameInput.prefetchRows], and inside a row only the programmes that intersect the
 * visible time band plus [GridFrameInput.prefetchMs] are materialised. The block count that comes out is
 * the `drawnBlocks` number `PERF_EPG_GRID` reports, and it must stay in the visible-block order of
 * magnitude — if it tracks [GridFrame.totalBlocks] instead, virtualisation is gone (S3: 119 vs 5922).
 *
 * It also answers the third item of the work order — what a channel with no data looks like — by
 * emitting placeholders, and it distinguishes the two honest reasons: `NO_EPG` (never bound to a guide)
 * and `LOADING` (bound, inside the prefetch margin, query not back yet). Gap placeholders inside a loaded
 * guide are emitted only for holes at least [GAP_LABEL_MINUTES] wide, so a one-minute artefact does not
 * paint a stripe.
 */
class GridFrameBuilder(private val timeAxis: TimeAxis) {

    fun build(input: GridFrameInput): GridFrame {
        val rows = input.rows
        val geometry = GridGeometry(
            metrics = input.metrics,
            viewportWidthPx = input.viewportWidthPx,
            viewportHeightPx = input.viewportHeightPx,
            contentWindow = input.contentWindow,
            rowCount = rows.size,
            scroll = input.scroll,
        )

        val visibleWindow = geometry.visibleTimeWindow(input.prefetchMs)
        val ticks = timeAxis.ticks(visibleWindow.fromMs, visibleWindow.toMs)
        val cornerLabel = timeAxis.dayLabel(input.contentWindow.fromMs)

        if (rows.isEmpty()) {
            return GridFrame(
                geometry = geometry,
                rows = emptyList(),
                ticks = ticks,
                nowMs = input.nowMs,
                selection = input.selection,
                cornerLabel = cornerLabel,
                drawnBlocks = 0,
                totalBlocks = 0,
                drawnPlaceholders = 0,
                visibleTimeWindow = visibleWindow,
            )
        }

        val visibleRows = geometry.visibleRows()
        val firstRow = max(0, visibleRows.first - input.prefetchRows)
        val lastRow = min(rows.size - 1, visibleRows.last + input.prefetchRows)

        val leftEdgePx = geometry.xForTime(visibleWindow.fromMs)
        val rightEdgePx = geometry.xForTime(visibleWindow.toMs)
        val gapThresholdPx = input.metrics.pxPerMinute * GAP_LABEL_MINUTES

        val rowFrames = ArrayList<GridRowFrame>(lastRow - firstRow + 1)
        var drawnBlocks = 0
        var drawnPlaceholders = 0

        for (rowIndex in firstRow..lastRow) {
            val rowFrame = RowBuilder(
                rowIndex = rowIndex,
                row = rows[rowIndex],
                selection = input.selection,
                leftEdgePx = leftEdgePx,
                rightEdgePx = rightEdgePx,
                gapThresholdPx = gapThresholdPx,
                geometry = geometry,
                timeAxis = timeAxis,
            ).build()
            drawnBlocks += rowFrame.blocks.size
            drawnPlaceholders += rowFrame.placeholders.size
            rowFrames += rowFrame
        }

        // What a non-virtualised draw would have visited: every loaded row's whole guide.
        var totalBlocks = 0
        for (row in rows) totalBlocks += row.index?.size ?: 0

        return GridFrame(
            geometry = geometry,
            rows = rowFrames,
            ticks = ticks,
            nowMs = input.nowMs,
            selection = input.selection,
            cornerLabel = cornerLabel,
            drawnBlocks = drawnBlocks,
            totalBlocks = totalBlocks,
            drawnPlaceholders = drawnPlaceholders,
            visibleTimeWindow = visibleWindow,
        )
    }

    /** One row's materialisation: clip to the visible band, emit blocks, then fill the holes. */
    private class RowBuilder(
        private val rowIndex: Int,
        private val row: GridRowInput,
        private val selection: GridSelection?,
        private val leftEdgePx: Float,
        private val rightEdgePx: Float,
        private val gapThresholdPx: Float,
        private val geometry: GridGeometry,
        private val timeAxis: TimeAxis,
    ) {
        fun build(): GridRowFrame {
            val blocks = ArrayList<GridBlock>()
            val placeholders = ArrayList<GridPlaceholder>()
            val focused = selection?.rowIndex == rowIndex
            val index = row.index

            when {
                row.state == EpgRowState.LOADING -> placeholders += placeholder(
                    PlaceholderKind.LOADING,
                    leftEdgePx,
                    rightEdgePx,
                )

                index == null || index.isEmpty -> placeholders += placeholder(
                    PlaceholderKind.NO_EPG,
                    leftEdgePx,
                    rightEdgePx,
                )

                else -> {
                    val from = geometry.timeForX(leftEdgePx)
                    val to = geometry.timeForX(rightEdgePx)
                    var cursorPx = leftEdgePx
                    for (programme in index.intersecting(from, to)) {
                        val left = max(geometry.xForTime(programme.startMs), leftEdgePx)
                        val right = min(geometry.xForTime(programme.stopMs), rightEdgePx)
                        if (right - left <= 0f) continue
                        if (left - cursorPx >= gapThresholdPx) {
                            placeholders += placeholder(PlaceholderKind.GAP, cursorPx, left)
                        }
                        blocks += GridBlock(
                            rowIndex = rowIndex,
                            channelId = row.channelId,
                            programmeId = programme.id,
                            title = programme.title,
                            timeLabel = timeAxis.label(programme.startMs),
                            startMs = programme.startMs,
                            stopMs = programme.stopMs,
                            leftPx = left,
                            rightPx = right,
                            clippedStart = programme.startMs < from,
                            clippedStop = programme.stopMs > to,
                            selected = focused && selection?.programmeId == programme.id,
                        )
                        cursorPx = right
                    }
                    if (rightEdgePx - cursorPx >= gapThresholdPx) {
                        placeholders += placeholder(PlaceholderKind.GAP, cursorPx, rightEdgePx)
                    }
                }
            }

            return GridRowFrame(
                rowIndex = rowIndex,
                channelId = row.channelId,
                name = row.name,
                channelNo = row.channelNo,
                state = row.state,
                topPx = geometry.yForRow(rowIndex),
                focused = focused,
                blocks = blocks,
                placeholders = placeholders,
            )
        }

        private fun placeholder(kind: PlaceholderKind, leftPx: Float, rightPx: Float) = GridPlaceholder(
            rowIndex = rowIndex,
            channelId = row.channelId,
            kind = kind,
            label = "",
            fromMs = geometry.timeForX(leftPx),
            toMs = geometry.timeForX(rightPx),
            leftPx = leftPx,
            rightPx = rightPx,
        )
    }

    companion object {
        /** Holes narrower than this are noise, not "the guide has a gap here". */
        const val GAP_LABEL_MINUTES = 15f
    }
}
