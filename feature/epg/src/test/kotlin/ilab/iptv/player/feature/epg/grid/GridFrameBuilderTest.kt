package ilab.iptv.player.feature.epg.grid

import com.google.common.truth.Truth.assertThat
import java.util.TimeZone
import org.junit.Test

class GridFrameBuilderTest {

    private val metrics = tvMetrics()
    private val axis = TimeAxis(TimeZone.getTimeZone("Asia/Shanghai"))
    private val builder = GridFrameBuilder(axis)
    private val window = TimeWindow(baseMs(), baseMs() + 6 * HOUR_MS)

    private fun baseMs(): Long = 1_800_000_000_000L

    private fun input(
        rows: List<GridRowInput>,
        scroll: ScrollOffset = ScrollOffset.ZERO,
        selection: GridSelection? = null,
        nowMs: Long? = null,
        window: TimeWindow = this.window,
    ) = GridFrameInput(
        metrics = metrics,
        viewportWidthPx = TV_WIDTH_PX,
        viewportHeightPx = TV_HEIGHT_PX,
        contentWindow = window,
        scroll = scroll,
        rows = rows,
        selection = selection,
        nowMs = nowMs,
    )

    @Test
    fun `only the visible band plus prefetch is materialised`() {
        val rows = loadedRows(60, window.fromMs, hours = 6)
        val frame = builder.build(input(rows))

        // Viewport 1080 px, header 80 px, row 112 px, prefetch 2 rows each side.
        assertThat(frame.rows.first().rowIndex).isEqualTo(0)
        assertThat(frame.rows.last().rowIndex).isEqualTo(10)
        assertThat(frame.rows.size).isEqualTo(11)
        assertThat(frame.rows.all { it.blocks.isNotEmpty() }).isTrue()
    }

    @Test
    fun `the block count stays near the visible rectangle, not the guide`() {
        val rows = loadedRows(658, window.fromMs, hours = 6)
        val frame = builder.build(input(rows))

        // The S3 comparison: 119 visible blocks vs 5922 in the whole guide.
        assertThat(frame.totalBlocks).isAtLeast(5000)
        assertThat(frame.drawnBlocks).isLessThan(400)
        assertThat(frame.virtualizationSkipRatio).isGreaterThan(0.9)
    }

    @Test
    fun `blocks are clipped to the visible time band`() {
        // Scroll far left so the leftmost content is off-pane; every visible block must start inside it.
        val rows = loadedRows(10, window.fromMs, hours = 6)
        val frame = builder.build(input(rows, scroll = ScrollOffset(600, 0)))
        val leftEdge = frame.visibleTimeWindow.fromMs
        val rightEdge = frame.visibleTimeWindow.toMs

        val blocks = frame.rows.flatMap { it.blocks }
        assertThat(blocks).isNotEmpty()
        assertThat(blocks.all { it.stopMs > leftEdge && it.startMs < rightEdge }).isTrue()
        assertThat(blocks.any { it.clippedStart }).isTrue()
        // Nothing is materialised beyond the window, not even clipped to zero width.
        assertThat(blocks.all { it.rightPx > it.leftPx }).isTrue()
    }

    @Test
    fun `a programme running at the left edge keeps its real start`() {
        val rows = listOf(
            GridRowInput(
                channelId = 1,
                name = "News",
                channelNo = 1,
                state = EpgRowState.LOADED,
                index = ProgrammeIndex.of(
                    listOf(programme(1, "news", window.fromMs - 30 * MINUTE_MS, 90, "Running")),
                ),
            ),
        )
        val frame = builder.build(input(rows))
        val block = frame.rows.single().blocks.single()
        assertThat(block.clippedStart).isTrue()
        // The grid never rewrites the programme's start time to the window edge.
        assertThat(block.startMs).isEqualTo(window.fromMs - 30 * MINUTE_MS)
        // The block is materialised with the prefetch margin, so it starts at the frame's left edge, not
        // the pane's (the fringe is drawn under the sticky chrome).
        assertThat(block.leftPx).isWithin(0.01f).of(frame.geometry.xForTime(frame.visibleTimeWindow.fromMs))
    }

    @Test
    fun `a window across midnight keeps both days on the ruler`() {
        // baseMs() is 16:00 local (Asia/Shanghai), so +6 h..+12 h straddles local midnight.
        val crossMidnight = TimeWindow(baseMs() + 6 * HOUR_MS, baseMs() + 12 * HOUR_MS)
        val rows = loadedRows(4, crossMidnight.fromMs, hours = 6)
        val frame = builder.build(input(rows, window = crossMidnight))

        assertThat(frame.ticks.any { it.isDayBoundary }).isTrue()
        assertThat(frame.cornerLabel).isEqualTo(axis.dayLabel(crossMidnight.fromMs))
        assertThat(frame.rows.flatMap { it.blocks }).isNotEmpty()
    }

    @Test
    fun `a channel with no guide draws a no-EPG placeholder`() {
        val rows = listOf(
            GridRowInput(1, "No guide", null, EpgRowState.NO_EPG, null),
            GridRowInput(2, "Empty guide", null, EpgRowState.LOADED, ProgrammeIndex.empty),
        )
        val frame = builder.build(input(rows))
        assertThat(frame.drawnBlocks).isEqualTo(0)
        for (row in frame.rows) {
            val placeholder = row.placeholders.single()
            assertThat(placeholder.kind).isEqualTo(PlaceholderKind.NO_EPG)
            assertThat(placeholder.leftPx).isWithin(0.01f).of(frame.geometry.xForTime(frame.visibleTimeWindow.fromMs))
            assertThat(placeholder.rightPx).isWithin(0.01f).of(frame.geometry.xForTime(frame.visibleTimeWindow.toMs))
        }
    }

    @Test
    fun `a row inside the prefetch margin but not answered yet draws a loading placeholder`() {
        val rows = loadedRows(3, window.fromMs, hours = 6).map { row ->
            if (row.channelId == 2L) row.copy(state = EpgRowState.LOADING, index = null) else row
        }
        val frame = builder.build(input(rows))
        val loading = frame.rows.first { it.rowIndex == 1 }
        assertThat(loading.placeholders.single().kind).isEqualTo(PlaceholderKind.LOADING)
        assertThat(loading.blocks).isEmpty()
    }

    @Test
    fun `holes in a guide at least fifteen minutes wide are shown`() {
        val rows = listOf(
            GridRowInput(
                channelId = 1,
                name = "Gappy",
                channelNo = 1,
                state = EpgRowState.LOADED,
                index = ProgrammeIndex.of(
                    listOf(
                        programme(1, "g", window.fromMs, 60, "First"),
                        // A two-hour hole, then one programme at the far end.
                        programme(2, "g", window.fromMs + 3 * HOUR_MS, 60, "Second"),
                    ),
                ),
            ),
        )
        val frame = builder.build(input(rows))
        val row = frame.rows.single()
        assertThat(row.blocks.map { it.title }).containsExactly("First", "Second").inOrder()
        // Ignore the leading prefetch fringe (the band is wider than the pane); the hole is the real one.
        val gap = row.placeholders.first { it.kind == PlaceholderKind.GAP && it.fromMs == window.fromMs + HOUR_MS }
        assertThat(gap.fromMs).isEqualTo(window.fromMs + HOUR_MS)
        assertThat(gap.toMs).isEqualTo(window.fromMs + 3 * HOUR_MS)
        assertThat(row.placeholders.count { it.kind == PlaceholderKind.GAP }).isAtLeast(2)
    }

    @Test
    fun `selection marks exactly one block, on the focused row`() {
        val rows = loadedRows(5, window.fromMs, hours = 6)
        val target = rows[1].index!!.at(window.fromMs + 45 * MINUTE_MS)!!
        val frame = builder.build(
            input(rows, selection = GridSelection(rowIndex = 1, timeMs = target.startMs, programmeId = target.id)),
        )
        val selected = frame.rows.flatMap { it.blocks }.filter { it.selected }
        assertThat(selected).hasSize(1)
        assertThat(selected.single().rowIndex).isEqualTo(1)
        assertThat(selected.single().programmeId).isEqualTo(target.id)
        assertThat(frame.rows.first { it.rowIndex == 1 }.focused).isTrue()
    }

    @Test
    fun `an empty channel table draws an empty frame instead of failing`() {
        val frame = builder.build(input(emptyList()))
        assertThat(frame.rows).isEmpty()
        assertThat(frame.drawnBlocks).isEqualTo(0)
        assertThat(frame.totalBlocks).isEqualTo(0)
        assertThat(frame.virtualizationSkipRatio).isEqualTo(1.0)
        assertThat(frame.ticks).isNotEmpty()
    }

    @Test
    fun `scrolling to the last row clamps the prefetch at the end of the list`() {
        val rows = loadedRows(20, window.fromMs, hours = 6)
        val frame = builder.build(input(rows, scroll = ScrollOffset(0, Int.MAX_VALUE)))
        assertThat(frame.rows.last().rowIndex).isEqualTo(19)
        assertThat(frame.rows.first().rowIndex).isEqualTo(9)
    }
}
