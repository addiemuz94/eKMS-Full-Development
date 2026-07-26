package com.ekms.terminal.hardware

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper

enum class NetworkTransport {
    ETHERNET,
    WIFI,
    OTHER,
    NONE,
}

data class NetworkStatus(
    val transport: NetworkTransport = NetworkTransport.NONE,
    /** Has a validated route to the internet, not just link-layer connectivity. */
    val hasInternet: Boolean = false,
    val message: String = "No network connection. Local operations continue; sync and pairing are paused until a network is available.",
)

/**
 * Observes device connectivity on every launch/restart and prefers Ethernet over Wi-Fi,
 * matching the terminal's physical deployment (a wired cabinet controller that may also have
 * Wi-Fi as a fallback). Read-only beyond one best-effort nudge: this never builds a new
 * credential-entry flow — Wi-Fi bring-up relies entirely on networks already known to the
 * device, exactly like a normal Android device reconnecting after a reboot.
 *
 * Ethernet detection deliberately goes through [ConnectivityManager]'s public
 * [NetworkCapabilities.TRANSPORT_ETHERNET], not `android.net.EthernetManager` — that class
 * requires system/privileged permissions on most OEM images and is not reliably usable from a
 * regular app, whereas `ConnectivityManager` only needs `ACCESS_NETWORK_STATE` and reports the
 * same transport information.
 */
class NetworkStatusController(
    context: Context,
    private val onStatusChanged: (NetworkStatus) -> Unit,
) {
    private val applicationContext = context.applicationContext
    private val connectivityManager =
        applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh()
        override fun onLost(network: Network) = refresh()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = refresh()
        override fun onUnavailable() = refresh()
    }

    @Volatile
    private var registered = false

    fun start() {
        if (registered) return
        registered = true
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { connectivityManager.registerNetworkCallback(request, networkCallback) }
        refresh()
    }

    fun stop() {
        if (!registered) return
        registered = false
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
    }

    /** Re-reads current connectivity; if nothing is up, best-effort-nudges Wi-Fi reconnect. */
    fun refresh() {
        val best = bestNetwork()
        val next = best ?: run {
            attemptWifiReconnectIfIdle()
            NetworkStatus()
        }
        mainHandler.post { onStatusChanged(next) }
    }

    /** Ethernet always wins when present, even mid-validation — never also attempts Wi-Fi then. */
    @Suppress("DEPRECATION")
    private fun bestNetwork(): NetworkStatus? {
        var ethernet: NetworkStatus? = null
        var wifi: NetworkStatus? = null

        for (network in connectivityManager.allNetworks) {
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: continue
            val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

            when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> {
                    ethernet = NetworkStatus(
                        transport = NetworkTransport.ETHERNET,
                        hasInternet = hasInternet,
                        message = if (hasInternet) {
                            "Connected via Ethernet."
                        } else {
                            "Ethernet link detected, confirming internet route…"
                        },
                    )
                }

                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                    wifi = NetworkStatus(
                        transport = NetworkTransport.WIFI,
                        hasInternet = hasInternet,
                        message = if (hasInternet) {
                            "Connected via Wi-Fi."
                        } else {
                            "Wi-Fi link detected, confirming internet route…"
                        },
                    )
                }
            }
        }

        return ethernet ?: wifi
    }

    /**
     * Best-effort only: modern Android (API 29+) does not let a regular app force a connection
     * to a specific saved SSID, or even reliably toggle the Wi-Fi radio — `reconnect()` is
     * deprecated and is a no-op on many OEM images for non-privileged callers. This exists so a
     * Wi-Fi radio that is enabled but idle gets nudged rather than sitting untried; the OS's own
     * auto-reconnect to previously-joined networks is what actually does the work in the common
     * case, exactly as a normal phone would behave after a reboot. No new pairing/credential UI.
     */
    @Suppress("DEPRECATION")
    private fun attemptWifiReconnectIfIdle() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        runCatching {
            if (wifiManager.isWifiEnabled) wifiManager.reconnect()
        }
    }
}
