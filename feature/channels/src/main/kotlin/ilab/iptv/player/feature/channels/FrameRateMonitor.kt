package ilab.iptv.player.feature.channels

import android.view.Choreographer

/**
 * Frame-interval sampler for the docs/02 §8.4 channel-list budget (`滚动 ≥ 50 fps`), measured the
 * way §8.5 fixes it: sample the frame interval over the scroll and report p50/p95 instead of a
 * hand-waved average.
 *
 * It observes the real `Choreographer`, so it measures what the user sees — including jank caused by
 * layout or focus churn, not just by our own code. Stats are published at most every
 * [PUBLISH_INTERVAL_NS] so the HUD costs nothing measurable itself.
 *
 * Caveat recorded in the P1-2 report: pushing 300 `input keyevent` calls over adb (~10/s) is slower
 * than a human holding the remote key, so the on-device numbers are a *lower* bound on scroll speed.
 */
class FrameRateMonitor(private val onStats: (Stats) -> Unit) {

    data class Stats(
        val frames: Int,
        val p50Ms: Double,
        val p95Ms: Double,
        val maxMs: Double,
        /** Equivalent average frames per second over the sampled window. */
        val fps: Double,
        /** Frames whose interval exceeded [JANK_THRESHOLD_MS] (docs/02 §8.5 60 Hz budget). */
        val jankyFrames: Int,
    )

    private val intervalsNs = ArrayList<Long>(2048)
    private var lastFrameNs = 0L
    private var lastPublishNs = 0L
    private var running = false

    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (lastFrameNs != 0L) {
                intervalsNs += frameTimeNanos - lastFrameNs
                if (frameTimeNanos - lastPublishNs >= PUBLISH_INTERVAL_NS) {
                    lastPublishNs = frameTimeNanos
                    onStats(stats())
                }
            }
            lastFrameNs = frameTimeNanos
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun start() {
        if (running) return
        running = true
        lastFrameNs = 0L
        lastPublishNs = 0L
        intervalsNs.clear()
        Choreographer.getInstance().postFrameCallback(callback)
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(callback)
    }

    /** Clears the sample so the HUD reports only the frames since the last reset. */
    fun reset() {
        intervalsNs.clear()
        lastFrameNs = 0L
        lastPublishNs = 0L
    }

    fun stats(): Stats {
        if (intervalsNs.isEmpty()) return Stats(0, 0.0, 0.0, 0.0, 0.0, 0)
        val sorted = intervalsNs.sorted()
        val p50 = percentile(sorted, 0.50)
        val p95 = percentile(sorted, 0.95)
        val max = sorted.last()
        val totalMs = sorted.sum() / 1_000_000.0
        val fps = if (totalMs > 0) sorted.size * 1000.0 / totalMs else 0.0
        val janky = sorted.count { it / 1_000_000.0 > JANK_THRESHOLD_MS }
        return Stats(
            frames = sorted.size,
            p50Ms = p50,
            p95Ms = p95,
            maxMs = max / 1_000_000.0,
            fps = fps,
            jankyFrames = janky,
        )
    }

    private fun percentile(sortedNs: List<Long>, fraction: Double): Double {
        if (sortedNs.isEmpty()) return 0.0
        val index = ((sortedNs.size - 1) * fraction).toInt().coerceIn(0, sortedNs.size - 1)
        return sortedNs[index] / 1_000_000.0
    }

    private companion object {
        /** docs/02 §8.5: the 60 Hz budget with the documented 0.3 ms tolerance (17.0 ms). */
        const val JANK_THRESHOLD_MS = 17.0
        const val PUBLISH_INTERVAL_NS = 500_000_000L
    }
}
