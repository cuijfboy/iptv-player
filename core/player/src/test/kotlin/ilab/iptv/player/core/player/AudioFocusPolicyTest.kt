package ilab.iptv.player.core.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * P1-7 item 3: the focus policy is where the work package's tradeoff lives, so every row of the table
 * is asserted here instead of being described in a comment: what a loss does to the stream, and what
 * the gain that follows it brings back.
 */
class AudioFocusPolicyTest {

    @Test
    fun `a gain while already active changes nothing`() {
        val policy = AudioFocusPolicy()

        val decision = policy.onEvent(AudioFocusEvent.GAIN)

        assertThat(decision).isEqualTo(AudioFocusDecision())
        assertThat(policy.state).isEqualTo(AudioFocusPolicy.State.ACTIVE)
    }

    @Test
    fun `a transient loss pauses and the gain that follows resumes at full volume`() {
        val policy = AudioFocusPolicy()

        val loss = policy.onEvent(AudioFocusEvent.LOSS_TRANSIENT)
        assertThat(loss.pause).isTrue()
        assertThat(loss.abandonFocus).isFalse()
        assertThat(policy.state).isEqualTo(AudioFocusPolicy.State.SUSPENDED)

        val gain = policy.onEvent(AudioFocusEvent.GAIN)
        assertThat(gain.resume).isTrue()
        // Restoring in the same step matters: a duck that was upgraded to a pause must not come back
        // at 20 %.
        assertThat(gain.restoreVolume).isTrue()
        assertThat(policy.state).isEqualTo(AudioFocusPolicy.State.ACTIVE)
    }

    @Test
    fun `a permanent loss pauses and gives the focus up`() {
        val policy = AudioFocusPolicy()

        val decision = policy.onEvent(AudioFocusEvent.LOSS)

        assertThat(decision.pause).isTrue()
        assertThat(decision.abandonFocus).isTrue()
        assertThat(policy.state).isEqualTo(AudioFocusPolicy.State.SUSPENDED)
    }

    @Test
    fun `the can-duck row ducks the live stream instead of pausing it`() {
        val policy = AudioFocusPolicy(duckVolume = 0.25f)

        val decision = policy.onEvent(AudioFocusEvent.LOSS_TRANSIENT_CAN_DUCK)

        // The documented tradeoff: a live channel keeps its edge and its buffer.
        assertThat(decision.duck).isTrue()
        assertThat(decision.pause).isFalse()
        assertThat(decision.volume).isEqualTo(0.25f)
        assertThat(policy.state).isEqualTo(AudioFocusPolicy.State.DUCKED)

        val gain = policy.onEvent(AudioFocusEvent.GAIN)
        assertThat(gain.restoreVolume).isTrue()
        assertThat(gain.resume).isFalse()
        assertThat(policy.state).isEqualTo(AudioFocusPolicy.State.ACTIVE)
    }

    @Test
    fun `a duck request while already suspended is ignored`() {
        val policy = AudioFocusPolicy()
        policy.onEvent(AudioFocusEvent.LOSS_TRANSIENT)

        val decision = policy.onEvent(AudioFocusEvent.LOSS_TRANSIENT_CAN_DUCK)

        assertThat(decision).isEqualTo(AudioFocusDecision())
        assertThat(policy.state).isEqualTo(AudioFocusPolicy.State.SUSPENDED)
    }

    @Test
    fun `the end of a session always releases the focus`() {
        val policy = AudioFocusPolicy()

        val decision = policy.onSessionEnded()

        assertThat(decision.abandonFocus).isTrue()
        assertThat(decision.pause).isFalse()
    }
}
