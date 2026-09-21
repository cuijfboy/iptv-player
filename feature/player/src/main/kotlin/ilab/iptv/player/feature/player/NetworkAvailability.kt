package ilab.iptv.player.feature.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * "The device's network went away / came back" (P1-7 item 5), behind an interface so the player
 * screen's retry wiring can be driven by a fake in tests.
 *
 * The real implementation is [ConnectivityNetworkAvailability]; the interface exists because the
 * *decision* to retry (once per outage, only in a failure state — [NetworkRetryGate]) is the part with
 * a right and a wrong answer, and `ConnectivityManager` cannot be exercised on the JVM.
 */
interface NetworkAvailability {

    /** Registers the listener. Both callbacks arrive on the main thread. */
    fun start(onLost: () -> Unit, onAvailable: () -> Unit)

    /** Unregisters; safe to call when not started. */
    fun stop()
}

/**
 * `ConnectivityManager`-backed implementation.
 *
 * `registerDefaultNetworkCallback` is used where it exists (API 24+): the question this feature asks
 * is "can the app reach the stream again", and that is about the *default* network, not about any
 * particular interface. On API 21–23 it falls back to a request for an internet-capable network.
 *
 * `onUnavailable` is routed to `onLost` on purpose: the network that could not be brought up is the
 * same outage from the player's point of view, and it must not leave the gate thinking it is online.
 */
class ConnectivityNetworkAvailability(context: Context) : NetworkAvailability {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            onAvailableAction?.invoke()
        }

        override fun onLost(network: Network) {
            onLostAction?.invoke()
        }

        override fun onUnavailable() {
            onLostAction?.invoke()
        }
    }

    private var onLostAction: (() -> Unit)? = null
    private var onAvailableAction: (() -> Unit)? = null

    override fun start(onLost: () -> Unit, onAvailable: () -> Unit) {
        if (registered) return
        val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        onLostAction = onLost
        onAvailableAction = onAvailable
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // API 26+ can be handed a Handler, so the callback lands on the main thread.
                manager.registerDefaultNetworkCallback(callback, handler)
            } else {
                // API 21–25 deliver on ConnectivityManager's own thread; NetworkRetryGate is
                // synchronized and the caller only launches work, so no thread hop is required.
                manager.registerNetworkCallback(
                    NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build(),
                    callback,
                )
            }
        }.isSuccess
        registered = ok
    }

    override fun stop() {
        if (!registered) return
        val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        runCatching { manager?.unregisterNetworkCallback(callback) }
        registered = false
        onLostAction = null
        onAvailableAction = null
    }
}
