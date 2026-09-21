package ilab.iptv.player.feature.player

/**
 * The last piece of P1-7 item 5: [NetworkAvailability] (the platform events) → [NetworkRetryGate] (the
 * decision) → the retry path P1-5 already owns.
 *
 * WHY IT IS ITS OWN CLASS: on the device this wiring cannot be exercised honestly. The living-room TV
 * has exactly one link (ethernet) and `adb` runs over it, so nothing can take the network away and put
 * it back without also cutting the cable the evidence travels on — measured 2026-09-22: airplane mode
 * left eth0 up and `VALIDATED`, so the app never saw an outage. Keeping the wiring in a class with two
 * dependencies (the availability source and the retry lambda) makes it testable on the JVM instead of
 * resting on a device experiment that cannot be run.
 */
class NetworkRetryWire(
    private val availability: NetworkAvailability,
    private val inFailureState: () -> Boolean,
    private val retry: () -> Unit,
    private val gate: NetworkRetryGate = NetworkRetryGate(),
    /** Where the released retry is reported (`PLAY_NET_RETRY`); null in the pure-logic tests. */
    private val events: PlaybackSystemEvents? = null,
    /** The channel that is being retried, read at the moment of the callback. */
    private val channelId: () -> Long? = { null },
) {

    /** How many retries the network has released so far (evidence + tests). */
    val retriesReleased: Int get() = gate.retriesReleased

    fun attach() {
        availability.start(
            onLost = gate::onNetworkLost,
            onAvailable = {
                // The gate answers false for the registration callback and for any availability that
                // is not preceded by an observed outage, so this cannot restart a healthy channel.
                if (gate.onNetworkAvailable(inFailureState())) {
                    events?.networkRetry(attempt = gate.retriesReleased, channelId = channelId())
                    retry()
                }
            },
        )
    }

    fun detach() = availability.stop()
}
