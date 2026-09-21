package ilab.iptv.player.core.source

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.source.pipeline.ConcurrencyGovernor
import ilab.iptv.player.core.source.pipeline.PipelineLimits
import ilab.iptv.player.core.source.pipeline.PlaybackPrioritySignal
import org.junit.Test

class ConcurrencyGovernorTest {

    private val limits = PipelineLimits()

    @Test
    fun `concurrency is unchanged when nothing is playing`() {
        val governor = ConcurrencyGovernor(limits, PlaybackPrioritySignal { false }, respectPlayback = true)
        assertThat(governor.fetchConcurrency()).isEqualTo(4)
        assertThat(governor.shallowConcurrency()).isEqualTo(12)
        assertThat(governor.deepConcurrency()).isEqualTo(6)
    }

    @Test
    fun `concurrency halves while playing`() {
        val governor = ConcurrencyGovernor(limits, PlaybackPrioritySignal { true }, respectPlayback = true)
        assertThat(governor.fetchConcurrency()).isEqualTo(2)
        assertThat(governor.shallowConcurrency()).isEqualTo(6)
        assertThat(governor.deepConcurrency()).isEqualTo(3)
    }

    @Test
    fun `respectPlayback false ignores the playback state`() {
        val governor = ConcurrencyGovernor(limits, PlaybackPrioritySignal { true }, respectPlayback = false)
        assertThat(governor.shallowConcurrency()).isEqualTo(12)
    }

    @Test
    fun `halving never drops below one worker`() {
        val governor = ConcurrencyGovernor(
            limits.copy(fetchConcurrency = 1, shallowConcurrency = 1),
            PlaybackPrioritySignal { true },
            respectPlayback = true,
        )
        assertThat(governor.fetchConcurrency()).isEqualTo(1)
        assertThat(governor.shallowConcurrency()).isEqualTo(1)
    }
}
