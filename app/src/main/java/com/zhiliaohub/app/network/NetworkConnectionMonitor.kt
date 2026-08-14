package com.zhiliaohub.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network

class NetworkConnectionMonitor(
    context: Context,
    private val onNetworkChanged: () -> Unit,
) {
    private val connectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private var isRegistered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            onNetworkChanged()
        }

        override fun onLost(network: Network) {
            onNetworkChanged()
        }

        override fun onUnavailable() {
            onNetworkChanged()
        }
    }

    @Synchronized
    fun start() {
        if (isRegistered) return
        connectivityManager.registerDefaultNetworkCallback(callback)
        isRegistered = true
    }

    @Synchronized
    fun stop() {
        if (!isRegistered) return
        connectivityManager.unregisterNetworkCallback(callback)
        isRegistered = false
    }
}
