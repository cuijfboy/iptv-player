package ilab.iptv.player.core.source

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.common.PlaybackActivity
import ilab.iptv.player.core.source.di.SourceModule
import ilab.iptv.player.core.source.pipeline.ConcurrencyGovernor
import ilab.iptv.player.core.source.pipeline.PipelineLimits
import org.junit.Test

/**
 * P2-5 closes the P2-4a open item "the production R7 signal always reports false": the binding now
 * reads [PlaybackActivity], the flag `:core:player`'s state machine writes. This test pins the seam
 * end to end — flag → signal → halved concurrency — without a device.
 */
class PlaybackPrioritySignalTest {

    @Test
    fun `the production signal follows the playback flag and halves concurrency`() {
        val signal = SourceModule.providePlaybackPrioritySignal()
        val governor = ConcurrencyGovernor(PipelineLimits(), signal, respectPlayback = true)

        try {
            PlaybackActivity.setActive(false)
            assertThat(signal.isPlaybackActive()).isFalse()
            assertThat(governor.fetchConcurrency()).isEqualTo(4)
            assertThat(governor.shallowConcurrency()).isEqualTo(12)
            assertThat(governor.deepConcurrency()).isEqualTo(6)

            PlaybackActivity.setActive(true)
            assertThat(signal.isPlaybackActive()).isTrue()
            assertThat(governor.fetchConcurrency()).isEqualTo(2)
            assertThat(governor.shallowConcurrency()).isEqualTo(6)
            assertThat(governor.deepConcurrency()).isEqualTo(3)
        } finally {
            PlaybackActivity.setActive(false)
        }
    }
}
