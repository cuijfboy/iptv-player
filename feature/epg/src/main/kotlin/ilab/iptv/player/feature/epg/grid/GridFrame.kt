package ilab.iptv.player.feature.epg.grid

import ilab.iptv.player.core.model.Programme

/** What a row's EPG looks like to the grid. `LOADING` and `NO_EPG` are *different* placeholders. */
enum class EpgRowState {
    /** The window query has answered for this channel; [GridRowInput.index] is authoritative. */
    LOADED,

    /** The channel has no `epg_channel_id`, so no query will ever answer (docs/02 §5.1). */
    NO_EPG,

    /** Inside the prefetch margin but the window query has not answered yet. */
    LOADING,
}

/** One channel row as the frame builder sees it. */
data class GridRowInput(
    val channelId: Long,
    val name: String,
    val channelNo: Int?,
    val state: EpgRowState,
    val index: ProgrammeIndex?,
)

/** The grid's cursor: which channel row and which instant (docs/02 §8.2 网格页焦点路由). */
data class GridSelection(
    val rowIndex: Int,
    val timeMs: Long,
    /** Null when the cursor sits in a gap — the detail layer says so instead of inventing a title. */
    val programmeId: Long?,
) {
    companion object {
        fun of(rowIndex: Int, timeMs: Long, row: GridRowInput?): GridSelection =
            GridSelection(rowIndex, timeMs, row?.index?.at(timeMs)?.id)
    }
}

/** One programme block, already in content pixels and clipped to the frame's visible time band. */
data class GridBlock(
    val rowIndex: Int,
    val channelId: Long,
    val programmeId: Long,
    val title: String,
    /** `HH:mm` of the start — docs/02 §8.3 asks the block to carry title *and* time. */
    val timeLabel: String,
    val startMs: Long,
    val stopMs: Long,
    val leftPx: Float,
    val rightPx: Float,
    val clippedStart: Boolean,
    val clippedStop: Boolean,
    val selected: Boolean,
) {
    val widthPx: Float get() = rightPx - leftPx
}

enum class PlaceholderKind { NO_EPG, LOADING, GAP }

/** A muted stretch of a row: no guide, not loaded yet, or a hole in the published guide. */
data class GridPlaceholder(
    val rowIndex: Int,
    val channelId: Long,
    val kind: PlaceholderKind,
    val label: String,
    val fromMs: Long,
    val toMs: Long,
    val leftPx: Float,
    val rightPx: Float,
)

/** One row of the frame: the channel identity plus what to draw inside its rectangle. */
data class GridRowFrame(
    val rowIndex: Int,
    val channelId: Long,
    val name: String,
    val channelNo: Int?,
    val state: EpgRowState,
    val topPx: Float,
    val focused: Boolean,
    val blocks: List<GridBlock>,
    val placeholders: List<GridPlaceholder>,
)

/**
 * Everything one frame needs, plus the two numbers §8.3 is measured on:
 *
 * - [drawnBlocks] — blocks actually materialised this frame (`drawnBlocks` in `PERF_EPG_GRID`);
 * - [totalBlocks] — blocks a non-virtualised draw would have visited across **all** rows whose guide is
 *   loaded. `drawnBlocks ≈ 119` against `totalBlocks ≈ 5922` is the S3 finding; the ratio is the
 *   virtualisation efficiency, and a ratio near 1 means virtualisation has been lost.
 */
data class GridFrame(
    val geometry: GridGeometry,
    val rows: List<GridRowFrame>,
    val ticks: List<TimeTick>,
    val nowMs: Long?,
    val selection: GridSelection?,
    val cornerLabel: String,
    val drawnBlocks: Int,
    val totalBlocks: Int,
    val drawnPlaceholders: Int,
    val visibleTimeWindow: TimeWindow,
) {
    /** Share of the loaded guide that this frame actually touched. Lower is better. */
    val virtualizationRatio: Double
        get() = if (totalBlocks <= 0) 0.0 else drawnBlocks.toDouble() / totalBlocks

    /** The complement of [virtualizationRatio]: the share of work virtualisation skipped. */
    val virtualizationSkipRatio: Double
        get() = if (totalBlocks <= 0) 1.0 else 1.0 - virtualizationRatio.coerceIn(0.0, 1.0)

    fun blockAt(rowIndex: Int, programmeId: Long): GridBlock? =
        rows.firstOrNull { it.rowIndex == rowIndex }?.blocks?.firstOrNull { it.programmeId == programmeId }
}

/** Input of one frame build: everything the virtualisation window is computed from. */
data class GridFrameInput(
    val metrics: GridMetrics,
    val viewportWidthPx: Int,
    val viewportHeightPx: Int,
    val contentWindow: TimeWindow,
    val scroll: ScrollOffset,
    val rows: List<GridRowInput>,
    val selection: GridSelection? = null,
    val nowMs: Long? = null,
    /** Rows drawn beyond the visible band so a fast scroll does not flash empty rows. */
    val prefetchRows: Int = 2,
    /** Milliseconds drawn beyond the visible time band, for the same reason. */
    val prefetchMs: Long = 15L * 60L * 1000L,
)

/** Convenience for callers that hold [Programme] lists instead of indexes. */
fun programmesToRowInput(
    channelId: Long,
    name: String,
    channelNo: Int?,
    programmes: List<Programme>?,
    hasBinding: Boolean,
): GridRowInput = when {
    !hasBinding -> GridRowInput(channelId, name, channelNo, EpgRowState.NO_EPG, null)
    programmes == null -> GridRowInput(channelId, name, channelNo, EpgRowState.LOADING, null)
    else -> GridRowInput(channelId, name, channelNo, EpgRowState.LOADED, ProgrammeIndex.of(programmes))
}
