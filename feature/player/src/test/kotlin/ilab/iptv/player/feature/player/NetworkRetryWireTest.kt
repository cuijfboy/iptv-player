package ilab.iptv.player.feature.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * P1-7 item 5's wiring: the platform events in, P1-5's retry path out.
 *
 * This is the half that cannot be shown on the device (the only TV link is the one `adb` runs over —
 * see [NetworkRetryWire]), so a fake [NetworkAvailability] stands in for `ConnectivityManager` and the
 * assertions are about what the app *does* with each callback.
 */
class NetworkRetryWireTest {

    @Test
    fun `attaching registers and detaching unregisters the listener`() {
        val availability = FakeNetworkAvailability()
        val wire = wire(availability)

        wire.attach()
        assertThat(availability.started).isTrue()

        wire.detach()
        assertThat(availability.stopped).isTrue()
    }

    @Test
    fun `the availability delivered at registration does not retry`() {
        val availability = FakeNetworkAvailability()
        val wire = wire(availability, failing = true)

        wire.attach()
        availability.available()

        assertThat(availability.retries).isEqualTo(0)
    }

    @Test
    fun `an outage followed by availability retries the failed channel exactly once`() {
        val availability = FakeNetworkAvailability()
        val wire = wire(availability, failing = true)
        wire.attach()

        availability.lost()
        availability.available()

        assertThat(availability.retries).isEqualTo(1)
        // ConnectivityManager re-delivers availability on capability changes (validation, bandwidth);
        // a second one within the same outage must not restart the session under the user again.
        availability.available()
        assertThat(availability.retries).isEqualTo(1)
    }

    @Test
    fun `a restore while the channel is healthy does not restart it`() {
        val availability = FakeNetworkAvailability()
        val wire = wire(availability, failing = false)
        wire.attach()

        availability.lost()
        availability.available()

        assertThat(availability.retries).isEqualTo(0)
    }

    @Test
    fun `a second outage earns a second retry`() {
        val availability = FakeNetworkAvailability()
        val wire = wire(availability, failing = true)
        wire.attach()

        availability.lost()
        availability.available()
        availability.lost()
        availability.available()

        assertThat(availability.retries).isEqualTo(2)
        assertThat(wire.retriesReleased).isEqualTo(2)
    }

    @Test
    fun `the failure state is read at the moment of the callback, not at attach time`() {
        val availability = FakeNetworkAvailability()
        var failing = false
        val wire = NetworkRetryWire(availability, inFailureState = { failing }, retry = availability::retry)
        wire.attach()

        availability.lost()
        failing = true
        availability.available()

        assertThat(availability.retries).isEqualTo(1)
    }

    private fun wire(availability: FakeNetworkAvailability, failing: Boolean = true) =
        NetworkRetryWire(
            availability = availability,
            inFailureState = { failing },
            retry = availability::retry,
        )

    /** Stands in for `ConnectivityManager` + the ViewModel's retry entry point. */
    private class FakeNetworkAvailability : NetworkAvailability {
        var started = false
        var stopped = false
        var retries = 0
        private var onLostAction: (() -> Unit)? = null
        private var onAvailableAction: (() -> Unit)? = null

        override fun start(onLost: () -> Unit, onAvailable: () -> Unit) {
            started = true
            onLostAction = onLost
            onAvailableAction = onAvailable
            // The real ConnectivityManager delivers the current network's availability immediately.
            onAvailable()
        }

        override fun stop() {
            stopped = true
        }

        fun lost() = onLostAction?.invoke() ?: error("listener not registered")

        fun available() = onAvailableAction?.invoke() ?: error("listener not registered")

        fun retry() {
            retries += 1
        }
    }
}
