package ilab.iptv.player.feature.epg.grid

import com.google.common.truth.Truth.assertThat
import java.util.TimeZone
import org.junit.Test

/**
 * The **offline** half of work order item 5: a runnable benchmark of the Canvas logic layer.
 *
 * What this measures and what it deliberately does not:
 *
 * - it drives the real [GridFrameBuilder] and the real [EpgGridRenderer] over a recorded [DrawSurface], so
 *   the numbers are the code's own per-frame work: blocks materialised, text layouts produced, draw calls;
 * - it does **not** produce fps. fps is a device property (`Choreographer` + GPU), and the dispatch leaves
 *   real-device fps to a later round because BUG-013 owns the TV. The report states that gap explicitly
 *   rather than dressing a JVM number as a frame rate.
 *
 * The scenario is the S3 prototype's: 658 channels, the prototype's 30/60-minute block mix, a 1920×1080
 * viewport at density 2.0, and the prototype's fling (700 px/s horizontally, 3500 px/s vertically, sampled
 * at 60 Hz for 30 s) bouncing inside the scroll range — so the numbers are comparable with S3's 1799-frame
 * virtualised run.
 */
class EpgGridBenchmarkTest {

    private val axis = TimeAxis(TimeZone.getTimeZone("Asia/Shanghai"))
    private val metrics = tvMetrics()
    private val builder = GridFrameBuilder(axis)
    private val renderer = EpgGridRenderer(axis)
    private val startMs = 1_800_000_000_000L

    @Test
    fun `per-frame work stays in the visible rectangle at both guide depths`() {
        val sixHours = run(hours = 6)
        val day = run(hours = 24)

        // The frozen expectations: ~119 blocks in the visible rectangle against ~5922 in the guide,
        // and a virtualisation ratio that collapses if the visible-rectangle rule is lost.
        assertThat(sixHours.totalBlocks).isAtLeast(5000)
        assertThat(sixHours.drawnBlocksMax).isLessThan(400)
        assertThat(sixHours.virtualizationSkipRatio).isGreaterThan(0.9)
        assertThat(sixHours.textCacheHitRate).isGreaterThan(0.6)
        // Rows never cover the whole list: 658 rows must not all be walked.
        assertThat(sixHours.rowsPerFrame).isAtMost(20)

        assertThat(day.totalBlocks).isGreaterThan(sixHours.totalBlocks)
        assertThat(day.drawnBlocksMax).isLessThan(400)
        assertThat(day.virtualizationSkipRatio).isGreaterThan(0.9)
    }

    /** One sweep through the scroll range, printing a machine-readable line for the report. */
    private fun run(hours: Int): BenchmarkResult {
        val window = TimeWindow(startMs, startMs + hours * HOUR_MS)
        val rows = loadedRows(658, startMs, hours)
        val store = TextLayoutStore(StubTextFactory())
        val surface = RecordingSurface(TV_WIDTH_PX, TV_HEIGHT_PX)
        val accumulator = GridPerfAccumulator()

        val geometryAtZero = GridGeometry(metrics, TV_WIDTH_PX, TV_HEIGHT_PX, window, rows.size, ScrollOffset.ZERO)
        val maxScrollX = geometryAtZero.maxScrollXPx
        val maxScrollY = geometryAtZero.maxScrollYPx

        var drawnBlocksMax = 0
        var rowsPerFrameMax = 0
        var textsPerFrameMax = 0
        var totalBlocks = 0
        var drawnTotal = 0L
        val frameNanos = ArrayList<Long>(FRAMES)

        // Warm-up: let the JIT compile the frame path before anything is recorded.
        repeat(WARMUP_FRAMES) { index ->
            val scroll = ScrollOffset(maxScrollX * index / WARMUP_FRAMES, 0)
            frame(window, rows, scroll, store, surface)
        }

        // The fling: the S3 prototype's two-axis inertia, sampled per frame, bouncing at the edges.
        var xPx = 0f
        var yPx = 0f
        var velocityX = 700f / 60f
        var velocityY = 3500f / 60f
        for (index in 0 until FRAMES) {
            xPx += velocityX
            yPx += velocityY
            if (xPx < 0f || xPx > maxScrollX) {
                velocityX = -velocityX
                xPx = xPx.coerceIn(0f, maxScrollX.toFloat())
            }
            if (yPx < 0f || yPx > maxScrollY) {
                velocityY = -velocityY
                yPx = yPx.coerceIn(0f, maxScrollY.toFloat())
            }
            val scroll = ScrollOffset(xPx.toInt(), yPx.toInt())
            val textsBefore = surface.texts
            val nanosBefore = System.nanoTime()
            val frame = frame(window, rows, scroll, store, surface)
            frameNanos += System.nanoTime() - nanosBefore

            drawnBlocksMax = maxOf(drawnBlocksMax, frame.drawnBlocks)
            rowsPerFrameMax = maxOf(rowsPerFrameMax, frame.rows.size)
            textsPerFrameMax = maxOf(textsPerFrameMax, surface.texts - textsBefore)
            totalBlocks = frame.totalBlocks
            drawnTotal += frame.drawnBlocks
            accumulator.onFrame(index * 16_670_000L, frame.drawnBlocks)
        }

        val perFrameMs = frameNanos.map { it / 1_000_000.0 }.sorted()
        val summary = accumulator.summary(store, 1.0 - drawnTotal.toDouble() / (totalBlocks.toLong() * FRAMES))
        println(
            "EPG_BENCH hours=$hours frames=$FRAMES rows=$totalBlocks totalBlocks=$totalBlocks " +
                "drawnBlocksAvg=${round2(summary.drawnBlocksAvg)} drawnBlocksP95=${summary.drawnBlocksP95} " +
                "drawnBlocksMax=$drawnBlocksMax rowsPerFrameMax=$rowsPerFrameMax textsPerFrameMax=$textsPerFrameMax " +
                "textCacheHits=${store.hits} textCacheMisses=${store.misses} " +
                "textCacheHitRate=${round4(store.hitRate())} " +
                "virtualizationSkipRatio=${round4(summary.virtualizationSkipRatio)} " +
                "frameWorkMsP50=${round3(Percentiles.nearestRank(perFrameMs, 50.0))} " +
                "frameWorkMsP95=${round3(Percentiles.nearestRank(perFrameMs, 95.0))}",
        )

        return BenchmarkResult(
            totalBlocks = totalBlocks,
            drawnBlocksMax = drawnBlocksMax,
            drawnBlocksP95 = summary.drawnBlocksP95,
            drawnBlocksAvg = summary.drawnBlocksAvg,
            rowsPerFrame = rowsPerFrameMax,
            textCacheHitRate = store.hitRate(),
            virtualizationSkipRatio = summary.virtualizationSkipRatio,
            frameWorkMsP95 = Percentiles.nearestRank(perFrameMs, 95.0),
        )
    }

    private fun frame(
        window: TimeWindow,
        rows: List<GridRowInput>,
        scroll: ScrollOffset,
        store: TextLayoutStore,
        surface: RecordingSurface,
    ): GridFrame {
        val frame = builder.build(
            GridFrameInput(
                metrics = metrics,
                viewportWidthPx = TV_WIDTH_PX,
                viewportHeightPx = TV_HEIGHT_PX,
                contentWindow = window,
                scroll = scroll,
                rows = rows,
                selection = GridSelection.of(0, window.fromMs + HOUR_MS, rows.firstOrNull()),
                nowMs = window.fromMs + 2 * HOUR_MS,
            ),
        )
        renderer.render(surface, store, frame)
        return frame
    }

    private fun round2(value: Double): Double = Math.round(value * 100.0) / 100.0
    private fun round3(value: Double): Double = Math.round(value * 1000.0) / 1000.0
    private fun round4(value: Double): Double = Math.round(value * 10000.0) / 10000.0

    private data class BenchmarkResult(
        val totalBlocks: Int,
        val drawnBlocksMax: Int,
        val drawnBlocksP95: Int,
        val drawnBlocksAvg: Double,
        val rowsPerFrame: Int,
        val textCacheHitRate: Double,
        val virtualizationSkipRatio: Double,
        val frameWorkMsP95: Double,
    )

    private companion object {
        /** 30 s at 60 Hz — the S3 sampling window (docs/02 §8.5). */
        const val FRAMES = 1800
        const val WARMUP_FRAMES = 60
    }
}
