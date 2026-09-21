package ilab.iptv.player.core.player

/**
 * The frozen playback tuning of docs/02 §7.5 plus the §7.3 prepare timeout. Kept as plain data so
 * the parameter mapping can be asserted in a unit test and so a future settings screen (P2-8) can
 * feed a different instance without touching the engine.
 *
 * `LIVE_DEFAULT` is exactly the frozen table; `LOW_LATENCY_LIVE` tightens the live-edge target for
 * the "直播低延迟优先" requirement (P1-3 item 3) without changing start-up behaviour.
 */
data class EngineTuning(
    val minBufferMs: Int = 1_500,
    val maxBufferMs: Int = 5_000,
    val bufferForPlaybackMs: Int = 800,
    val bufferForPlaybackAfterRebufferMs: Int = 1_500,
    val targetOffsetMs: Long = 3_000,
    val minPlaybackSpeed: Float = 0.97f,
    val maxPlaybackSpeed: Float = 1.03f,
    val stallThresholdMs: Long = 8_000,
    val maxRetrySame: Int = 1,
    val retryBackoffMs: Long = 1_000,
    val prepareTimeoutMs: Long = 12_000,
    val connectTimeoutMs: Int = 15_000,
    val readTimeoutMs: Int = 15_000,
    val preferPassthrough: Boolean = true,
) {
    /** Clamps a caller-supplied instance into a shape LoadControl will accept (never throws). */
    fun normalized(): EngineTuning {
        val max = maxBufferMs.coerceAtLeast(1_000)
        val min = minBufferMs.coerceIn(500, max)
        val lowSpeed = minPlaybackSpeed.coerceIn(MIN_SPEED, 1f)
        val highSpeed = maxPlaybackSpeed.coerceIn(1f, MAX_SPEED)
        return copy(
            minBufferMs = min,
            maxBufferMs = max,
            bufferForPlaybackMs = bufferForPlaybackMs.coerceIn(0, min),
            bufferForPlaybackAfterRebufferMs = bufferForPlaybackAfterRebufferMs.coerceIn(0, min),
            targetOffsetMs = targetOffsetMs.coerceAtLeast(0),
            minPlaybackSpeed = lowSpeed,
            maxPlaybackSpeed = highSpeed.coerceAtLeast(lowSpeed),
            stallThresholdMs = stallThresholdMs.coerceAtLeast(1_000),
            maxRetrySame = maxRetrySame.coerceAtLeast(0),
            retryBackoffMs = retryBackoffMs.coerceAtLeast(0),
            prepareTimeoutMs = prepareTimeoutMs.coerceAtLeast(1_000),
            connectTimeoutMs = connectTimeoutMs.coerceAtLeast(1_000),
            readTimeoutMs = readTimeoutMs.coerceAtLeast(1_000),
        )
    }

    companion object {
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 1.5f

        /** docs/02 §7.5 as frozen after S1/S5. */
        val LIVE_DEFAULT = EngineTuning()

        /** Live-first variant: chase the edge harder, keep the same start-up buffer. */
        val LOW_LATENCY_LIVE = LIVE_DEFAULT.copy(
            targetOffsetMs = 1_500,
            minPlaybackSpeed = 0.95f,
            maxPlaybackSpeed = 1.10f,
        )
    }
}
