package com.example.capsight.camera

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.example.capsight.TAG
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds on to the camera's Wi-Fi network (FR-RX-01, CLAUDE.md §6.3).
 *
 * BLENAV_CAM has no internet, so Android keeps mobile data as the default route
 * and would send our requests there. We ask for any Wi-Fi network *without*
 * requiring INTERNET, and open every camera connection through the [Network]
 * we get (per-connection binding, not bindProcessToNetwork). Works on API 24+.
 *
 * The user joins BLENAV_CAM in system Settings once; we never read the SSID (no
 * location permission) - the HTTP health probe identifies the camera.
 */
class CameraNetwork(context: Context) {
    private val connectivity =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _network = MutableStateFlow<Network?>(null)

    /** The Wi-Fi network to open camera sockets on, or null while there is none. */
    val network: StateFlow<Network?> = _network.asStateFlow()

    private var callback: ConnectivityManager.NetworkCallback? = null

    fun request() {
        if (callback != null) return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "Wi-Fi network available: $network")
                _network.value = network
            }

            override fun onLost(network: Network) {
                Log.i(TAG, "Wi-Fi network lost: $network")
                if (_network.value == network) _network.value = null
            }
        }
        connectivity.requestNetwork(request, cb)
        callback = cb
    }

    fun release() {
        callback?.let {
            try {
                connectivity.unregisterNetworkCallback(it)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "network callback already unregistered", e)
            }
        }
        callback = null
        _network.value = null
    }
}
