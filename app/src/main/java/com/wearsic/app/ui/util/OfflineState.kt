package com.wearsic.app.ui.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Watches the watch's actual network state, not guesses.
 *
 * A single NetworkCallback tracks whether ANY network with INTERNET
 * capability is available. The UI combines this with periodic server
 * health probes to decide the true status:
 *
 *   hasInternet = false          → OFFLINE (Wi-Fi/radio off)
 *   hasInternet = true, server ✗ → OFFLINE (server unreachable)
 *   otherwise                    → online
 *
 * [hasInternet] is null when the system never answered (no permission /
 * Robolectric sandbox) — callers treat null as "unknown, don't claim
 * offline".
 */
class OfflineState(context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _hasInternet = MutableStateFlow<Boolean?>(null)
    val hasInternet: StateFlow<Boolean?> = _hasInternet.asStateFlow()

    init {
        val cm = connectivityManager
        if (cm != null) {
        try {
            val current = cm.activeNetwork
            _hasInternet.value = current?.let { cm.getNetworkCapabilities(it) }
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        _hasInternet.value = true
                    }

                    override fun onLost(network: Network) {
                        _hasInternet.value = cm.activeNetwork?.let {
                            cm.getNetworkCapabilities(it)
                        }?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            ?: false
                    }

                    override fun onUnavailable() {
                        _hasInternet.value = false
                    }
                },
            )
        } catch (_: Exception) {
            _hasInternet.value = null
        }
        }
    }
}
