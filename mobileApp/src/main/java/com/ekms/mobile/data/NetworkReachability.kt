package com.ekms.mobile.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

data class MobileNetworkStatus(
    val hasInternet: Boolean = false,
    val transportLabel: String = "Offline",
)

/**
 * Observes validated internet connectivity for the companion app's Connected indicator.
 */
class NetworkReachability(
    context: Context,
    private val onChanged: (MobileNetworkStatus) -> Unit,
) {
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = publish()
        override fun onLost(network: Network) = publish()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = publish()
        override fun onUnavailable() = publish()
    }

    private var registered = false

    fun start() {
        if (registered) return
        registered = true
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { connectivityManager.registerNetworkCallback(request, callback) }
        publish()
    }

    fun stop() {
        if (!registered) return
        registered = false
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
    }

    private fun publish() {
        onChanged(currentStatus())
    }

    fun currentStatus(): MobileNetworkStatus {
        val network = connectivityManager.activeNetwork ?: return MobileNetworkStatus()
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return MobileNetworkStatus()
        val online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val label = when {
            !online -> "Offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Online"
        }
        return MobileNetworkStatus(hasInternet = online, transportLabel = label)
    }
}

@Composable
fun rememberMobileNetworkStatus(): MobileNetworkStatus {
    val context = LocalContext.current.applicationContext
    var status by remember { mutableStateOf(MobileNetworkStatus()) }
    val controller = remember(context) {
        NetworkReachability(context) { status = it }
    }
    DisposableEffect(controller) {
        controller.start()
        onDispose { controller.stop() }
    }
    return status
}
