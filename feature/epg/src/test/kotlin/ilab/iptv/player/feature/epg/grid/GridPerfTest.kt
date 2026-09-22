package ilab.iptv.player.feature.epg.grid

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GridPerfTest {

    @Test
    fun `percentiles use the nearest-rank convention`() {
        val sorted = (1..10).map { it.toDouble() }
        assertThat(Percentiles.nearestRank(sorted, 50.0)).isEqualTo(5.0)
        assertThat(Percentiles.nearestRank(sorted, 95.0)).isEqualTo(10.0)
        assertThat(Percentiles.nearestRank(sorted, 100.0)).isEqualTo(10.0)
        assertThat(Percentiles.nearestRank(emptyList(), 95.0)).isEqualTo(0.0)
    }

    @Test
    fun `the accumulator reports frame and block statistics`() {
        val accumulator = GridPerfAccumulator()
        // 16.67 ms frames, with one 30 ms outlier and one frame that drew a lot of blocks.
        accumulator.onFrame(0L, 100)
        var ns = 0L
        for (index in 1..100) {
            ns += if (index == 50) 30_000_000L else 16_670_000L
            accumulator.onFrame(ns, if (index == 60) 900 else 100 + index)
        }
        val store = TextLayoutStore(StubTextFactory())
        store.get("a", 1, GridTextStyle.BLOCK_TITLE)
        store.get("a", 1, GridTextStyle.BLOCK_TITLE)
        val summary = accumulator.summary(store, virtualizationSkipRatio = 0.979)

        assertThat(summary.frames).isEqualTo(100)
        assertThat(summary.frameIntervalP50Ms).isWithin(0.01).of(16.67)
        assertThat(summary.frameIntervalMaxMs).isWithin(0.01).of(30.0)
        assertThat(summary.avgFps).isWithin(0.1).of(1000.0 / 16.67)
        assertThat(summary.drawnBlocksMax).isEqualTo(900)
        assertThat(summary.drawnBlocksP95).isAtLeast(150)
        assertThat(summary.textCacheHits).isEqualTo(1)
        assertThat(summary.textCacheHitRate).isWithin(1e-9).of(0.5)
        assertThat(summary.virtualizationSkipRatio).isWithin(1e-9).of(0.979)
        assertThat(summary.sampledMs).isGreaterThan(0)
    }

    @Test
    fun `a debug build always reports the grid metrics`() {
        assertThat(PerfSamplingPolicy.shouldReport(isDebugBuild = true, roll = 0.999)).isTrue()
    }

    @Test
    fun `a release build reports only the sampled sessions`() {
        assertThat(PerfSamplingPolicy.shouldReport(isDebugBuild = false, roll = 0.05)).isTrue()
        assertThat(PerfSamplingPolicy.shouldReport(isDebugBuild = false, roll = 0.5)).isFalse()
        assertThat(PerfSamplingPolicy.shouldReport(isDebugBuild = false, roll = 0.0999)).isTrue()
    }
}
