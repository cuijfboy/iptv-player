package ilab.iptv.player.core.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pins docs/02 §7.5's frozen defaults, so a drifting edit shows up as a test failure. */
class EngineTuningTest {

    @Test
    fun `live default matches the frozen table`() {
        val tuning = EngineTuning.LIVE_DEFAULT
        assertThat(tuning.minBufferMs).isEqualTo(1_500)
        assertThat(tuning.maxBufferMs).isEqualTo(5_000)
        assertThat(tuning.bufferForPlaybackMs).isEqualTo(800)
        assertThat(tuning.targetOffsetMs).isEqualTo(3_000)
        assertThat(tuning.minPlaybackSpeed).isWithin(0.0001f).of(0.97f)
        assertThat(tuning.maxPlaybackSpeed).isWithin(0.0001f).of(1.03f)
        assertThat(tuning.stallThresholdMs).isEqualTo(8_000)
        assertThat(tuning.maxRetrySame).isEqualTo(1)
        assertThat(tuning.retryBackoffMs).isEqualTo(1_000)
        assertThat(tuning.prepareTimeoutMs).isEqualTo(12_000)
        assertThat(tuning.preferPassthrough).isTrue()
    }

    @Test
    fun `low latency variant only chases the live edge harder`() {
        val low = EngineTuning.LOW_LATENCY_LIVE
        assertThat(low.minBufferMs).isEqualTo(EngineTuning.LIVE_DEFAULT.minBufferMs)
        assertThat(low.maxBufferMs).isEqualTo(EngineTuning.LIVE_DEFAULT.maxBufferMs)
        assertThat(low.targetOffsetMs).isLessThan(EngineTuning.LIVE_DEFAULT.targetOffsetMs)
        assertThat(low.minPlaybackSpeed).isLessThan(EngineTuning.LIVE_DEFAULT.minPlaybackSpeed)
        assertThat(low.maxPlaybackSpeed).isGreaterThan(EngineTuning.LIVE_DEFAULT.maxPlaybackSpeed)
    }

    @Test
    fun `normalize clamps nonsense into a load-control-safe shape`() {
        val wild = EngineTuning(
            minBufferMs = -5,
            maxBufferMs = 10,
            bufferForPlaybackMs = 9_999,
            bufferForPlaybackAfterRebufferMs = -1,
            targetOffsetMs = -1,
            minPlaybackSpeed = 0f,
            maxPlaybackSpeed = 9f,
            stallThresholdMs = 0,
            maxRetrySame = -3,
            retryBackoffMs = -1,
            prepareTimeoutMs = 0,
            connectTimeoutMs = 0,
            readTimeoutMs = 0,
        ).normalized()

        assertThat(wild.maxBufferMs).isAtLeast(1_000)
        assertThat(wild.minBufferMs).isAtLeast(500)
        assertThat(wild.minBufferMs).isAtMost(wild.maxBufferMs)
        assertThat(wild.bufferForPlaybackMs).isAtLeast(0)
        assertThat(wild.bufferForPlaybackMs).isAtMost(wild.minBufferMs)
        assertThat(wild.bufferForPlaybackAfterRebufferMs).isAtLeast(0)
        assertThat(wild.targetOffsetMs).isAtLeast(0)
        assertThat(wild.minPlaybackSpeed).isAtLeast(EngineTuning.MIN_SPEED)
        assertThat(wild.maxPlaybackSpeed).isAtMost(EngineTuning.MAX_SPEED)
        assertThat(wild.maxPlaybackSpeed).isAtLeast(wild.minPlaybackSpeed)
        assertThat(wild.stallThresholdMs).isAtLeast(1_000)
        assertThat(wild.maxRetrySame).isAtLeast(0)
        assertThat(wild.retryBackoffMs).isAtLeast(0)
        assertThat(wild.prepareTimeoutMs).isAtLeast(1_000)
        assertThat(wild.connectTimeoutMs).isAtLeast(1_000)
        assertThat(wild.readTimeoutMs).isAtLeast(1_000)
    }

    @Test
    fun `normalize leaves a sane tuning untouched`() {
        assertThat(EngineTuning.LIVE_DEFAULT.normalized()).isEqualTo(EngineTuning.LIVE_DEFAULT)
    }
}
