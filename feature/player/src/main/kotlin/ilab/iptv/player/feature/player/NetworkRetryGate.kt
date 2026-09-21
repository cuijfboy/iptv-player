package ilab.iptv.player.feature.player

/**
 * "Network came back — retry the channel" (P1-7 item 5).
 *
 * WHAT THIS IS NOT: it is not a retry path. The retry is P1-5's (`PlayerViewModel.retry()` →
 * `PlaybackFailoverCoordinator.open`), and this class only decides **whether** that path should be
 * asked to run again — one time — after the network came back. Making a second retry mechanism here
 * would put two writers on the same session, which is exactly what §4.5 C1 forbids.
 *
 * WHY A GATE AT ALL: `ConnectivityManager` delivers `onAvailable` for the *current* network the
 * moment a callback is registered, and it re-delivers on every capability change (validation flips,
 * bandwidth re-estimates, a second interface coming up). Retrying on each of those would restart the
 * session under the user repeatedly, so a retry needs an observed outage behind it and a failure state
 * in front of it, and it happens at most once per outage.
 */
class NetworkRetryGate {

    /** True while the last thing we heard from ConnectivityManager was a usable network. */
    var isOnline: Boolean = true
        private set

    /** How many retries this gate has released; exposed for the device evidence and the tests. */
    var retriesReleased: Int = 0
        private set

    private var retriedSinceOutage = false

    /** `onLost` of the default network: the outage that must be observed before a retry counts. */
    fun onNetworkLost() {
        isOnline = false
        retriedSinceOutage = false
    }

    /** `onUnavailable` (the network exists but cannot serve requests) — same outage semantics. */
    fun onNetworkUnavailable() = onNetworkLost()

    /**
     * A network is available again. Returns `true` exactly once per outage, and only when playback is
     * in a failure state — a restore while the channel is fine must not restart anything.
     *
     * @param inFailureState the player screen is showing the failure overlay (its fault, or the
     *     session's `ERROR` phase). The caller evaluates it at the moment of the callback.
     */
    fun onNetworkAvailable(inFailureState: Boolean): Boolean {
        if (isOnline) return false
        isOnline = true
        if (!inFailureState || retriedSinceOutage) return false
        retriedSinceOutage = true
        retriesReleased += 1
        return true
    }
}
