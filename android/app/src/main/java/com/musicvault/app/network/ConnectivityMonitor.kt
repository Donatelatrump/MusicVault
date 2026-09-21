package com.musicvault.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Observes whether Android currently has a network with validated Internet access. */
class ConnectivityMonitor(context: Context) : AutoCloseable {
    private val connectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    private val _isOnline = MutableStateFlow(connectivityManager.hasValidatedInternet())
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh()
        override fun onLost(network: Network) = refresh()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = refresh()
        override fun onUnavailable() = refresh()
    }

    private var registered = false

    init {
        registered = runCatching {
            connectivityManager.registerDefaultNetworkCallback(callback)
        }.isSuccess
        refresh()
    }

    private fun refresh() {
        _isOnline.value = connectivityManager.hasValidatedInternet()
    }

    override fun close() {
        if (registered) runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        registered = false
    }
}

private fun ConnectivityManager.hasValidatedInternet(): Boolean =
    activeNetwork?.let(::getNetworkCapabilities)?.let { capabilities ->
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } == true
