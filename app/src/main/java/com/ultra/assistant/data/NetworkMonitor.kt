package com.ultra.assistant.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NetworkMonitor(context: Context) {
    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _isOnline = MutableStateFlow(computeConnectivity())
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val _isReconnecting = MutableStateFlow(false)
    val isReconnecting: StateFlow<Boolean> = _isReconnecting.asStateFlow()

    private var callback: ConnectivityManager.NetworkCallback? = null

    init {
        runCatching {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val nowOnline = computeConnectivity()
                    _isReconnecting.value = !nowOnline
                    _isOnline.value = nowOnline
                }

                override fun onLost(network: Network) {
                    val nowOnline = computeConnectivity(lostNetwork = network)
                    _isReconnecting.value = false
                    _isOnline.value = nowOnline
                }

                override fun onUnavailable() {
                    _isReconnecting.value = false
                    _isOnline.value = false
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    val hasInternet = hasInternetCaps(networkCapabilities)
                    val nowOnline = hasInternet || computeConnectivity()
                    _isReconnecting.value = false
                    _isOnline.value = nowOnline
                }
            }
            callback = cb
            connectivityManager?.registerNetworkCallback(request, cb)
        }
    }

    fun checkCurrentConnectivity(): Boolean {
        val online = computeConnectivity()
        if (_isOnline.value != online) {
            _isOnline.value = online
        }
        return online
    }

    fun unregister() {
        callback?.let { cb ->
            runCatching { connectivityManager?.unregisterNetworkCallback(cb) }
        }
        callback = null
    }

    private fun computeConnectivity(lostNetwork: Network? = null): Boolean {
        val cm = connectivityManager ?: return false
        return runCatching {
            val active = cm.activeNetwork
            if (active != null && active != lostNetwork) {
                val activeCaps = cm.getNetworkCapabilities(active)
                if (activeCaps != null && hasInternetCaps(activeCaps)) {
                    return@runCatching true
                }
            }
            @Suppress("DEPRECATION")
            val networks = cm.allNetworks
            networks.any { net ->
                if (lostNetwork != null && net == lostNetwork) return@any false
                val caps = cm.getNetworkCapabilities(net) ?: return@any false
                hasInternetCaps(caps)
            }
        }.getOrDefault(false)
    }

    private fun hasInternetCaps(caps: NetworkCapabilities): Boolean {
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
    }
}
