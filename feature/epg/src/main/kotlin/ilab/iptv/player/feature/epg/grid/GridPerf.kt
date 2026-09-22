package ilab.iptv.player.feature.epg.grid

import kotlin.math.ceil

/**
 * Nearest-rank percentile, the convention this floor uses everywhere (`docs/05-过程记录/07-Spike报告.md`
 * §1.2): the p-th percentile is the `ceil(p/100 × N)`-th sample of the ascending list, 1-based, with no
 * interpolation. Reused rather than re-derived, so a grid `p95` means the same thing as a spike `p95`.
 */
object Percentiles {

    /** [sorted] must be ascending; an empty list answers 0.0. */
    fun nearestRank(sorted: List<Double>, percentile: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val rank = ceil(percentile / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }
}

/** The numbers `PERF_EPG_GRID` carries and the P3-1 report tabulates. */
data class GridPerfSummary(
    val frames: Int,
    val sampledMs: Long,
    val frameIntervalP50Ms: Double,
    val frameIntervalP95Ms: Double,
    val frameIntervalMaxMs: Double,
    val avgFps: Double,
    val drawnBlocksAvg: Double,
    val drawnBlocksP95: Int,
    val drawnBlocksMax: Int,
    val textCacheHits: Int,
    val textCacheMisses: Int,
    val textCacheHitRate: Double,
    val virtualizationSkipRatio: Double,
)

/**
 * Accumulates one measurement window of frames. `drawnBlocks` is summarised with p95 and max as well as
 * the average, because the failure worth catching is one frame that materialised the whole guide, and an
 * average hides exactly that.
 */
class GridPerfAccumulator {

    private val intervals = ArrayList<Double>(512)
    private val drawnBlocks = ArrayList<Int>(512)
    private var sampledMs = 0L
    private var lastFrameNs = 0L
    private var hasPreviousFrame = false

    fun onFrame(frameTimeNanos: Long, drawnBlocksThisFrame: Int) {
        // A flag, not a sentinel: a first frame stamped 0 ns is legal and must not swallow an interval.
        if (hasPreviousFrame) {
            val deltaMs = (frameTimeNanos - lastFrameNs) / 1_000_000.0
            intervals += deltaMs
            sampledMs += deltaMs.toLong()
        }
        lastFrameNs = frameTimeNanos
        hasPreviousFrame = true
        drawnBlocks += drawnBlocksThisFrame
    }

    fun reset() {
        intervals.clear()
        drawnBlocks.clear()
        sampledMs = 0L
        lastFrameNs = 0L
        hasPreviousFrame = false
    }

    val frameCount: Int get() = intervals.size

    fun summary(textCacheHitRate: Double, virtualizationSkipRatio: Double): GridPerfSummary {
        val sortedIntervals = intervals.sorted()
        val sortedBlocks = drawnBlocks.sorted()
        val p50 = Percentiles.nearestRank(sortedIntervals, 50.0)
        return GridPerfSummary(
            frames = sortedIntervals.size,
            sampledMs = sampledMs,
            frameIntervalP50Ms = p50,
            frameIntervalP95Ms = Percentiles.nearestRank(sortedIntervals, 95.0),
            frameIntervalMaxMs = sortedIntervals.lastOrNull() ?: 0.0,
            avgFps = if (p50 > 0.0) 1000.0 / p50 else 0.0,
            drawnBlocksAvg = if (sortedBlocks.isEmpty()) 0.0 else sortedBlocks.average(),
            drawnBlocksP95 = Percentiles.nearestRank(sortedBlocks.map { it.toDouble() }, 95.0).toInt(),
            drawnBlocksMax = sortedBlocks.lastOrNull() ?: 0,
            textCacheHits = 0,
            textCacheMisses = 0,
            textCacheHitRate = textCacheHitRate,
            virtualizationSkipRatio = virtualizationSkipRatio,
        )
    }

    /** Same summary, with the cache counters taken from the store that produced the hit rate. */
    fun summary(store: TextLayoutStore, virtualizationSkipRatio: Double): GridPerfSummary =
        summary(store.hitRate(), virtualizationSkipRatio).copy(
            textCacheHits = store.hits,
            textCacheMisses = store.misses,
        )
}

/**
 * docs/02 §8.3: `PERF_EPG_GRID` is emitted in full in debug builds and sampled in release (10% of
 * sessions by default). `roll` comes from the injected random source, so the decision itself is testable
 * at both ends: 0.0 always reports, 0.999 never does in a release build.
 */
object PerfSamplingPolicy {

    const val RELEASE_SAMPLE_RATE = 0.10

    fun shouldReport(isDebugBuild: Boolean, roll: Double, releaseRate: Double = RELEASE_SAMPLE_RATE): Boolean =
        isDebugBuild || roll < releaseRate
}
