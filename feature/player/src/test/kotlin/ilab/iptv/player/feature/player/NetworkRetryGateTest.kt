package ilab.iptv.player.feature.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * P1-7 item 5: when a network restore is allowed to ask P1-5's retry path to run again.
 *
 * The two ways to get this wrong are opposite: retrying on every `onAvailable` (which
 * `ConnectivityManager` re-delivers on every capability change, restarting the session under the user)
 * and never retrying at all (the failure overlay stays up after the network came back).
 */
class NetworkRetryGateTest {

    @Test
    fun `the callback-registration availability is not a restore`() {
        val gate = NetworkRetryGate()

        // ConnectivityManager delivers onAvailable for the current network the moment a callback is
        // registered; at that point the screen is not in a failure state caused by a network change.
        assertThat(gate.onNetworkAvailable(inFailureState = true)).isFalse()
        assertThat(gate.retriesReleased).isEqualTo(0)
    }

    @Test
    fun `a restore while the channel is fine does not restart it`() {
        val gate = NetworkRetryGate()

        gate.onNetworkLost()
        val retried = gate.onNetworkAvailable(inFailureState = false)

        assertThat(retried).isFalse()
        assertThat(gate.isOnline).isTrue()
        assertThat(gate.retriesReleased).isEqualTo(0)
    }

    @Test
    fun `a restore against a failure state releases exactly one retry`() {
        val gate = NetworkRetryGate()
        gate.onNetworkLost()

        assertThat(gate.onNetworkAvailable(inFailureState = true)).isTrue()
        assertThat(gate.retriesReleased).isEqualTo(1)
        // The retry path was already asked; a second availability without a new outage must not ask
        // again (the retry itself may still be preparing at that moment).
        assertThat(gate.onNetworkAvailable(inFailureState = true)).isFalse()
        assertThat(gate.retriesReleased).isEqualTo(1)
    }

    @Test
    fun `an explicit unavailability counts as the outage`() {
        val gate = NetworkRetryGate()

        gate.onNetworkUnavailable()

        assertThat(gate.isOnline).isFalse()
        assertThat(gate.onNetworkAvailable(inFailureState = true)).isTrue()
    }

    @Test
    fun `each new outage earns its own retry`() {
        val gate = NetworkRetryGate()

        gate.onNetworkLost()
        assertThat(gate.onNetworkAvailable(inFailureState = true)).isTrue()
        gate.onNetworkLost()
        assertThat(gate.onNetworkAvailable(inFailureState = true)).isTrue()

        assertThat(gate.retriesReleased).isEqualTo(2)
    }
}
