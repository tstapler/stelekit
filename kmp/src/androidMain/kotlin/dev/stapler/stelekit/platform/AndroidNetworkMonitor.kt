// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dev.stapler.stelekit.logging.Logger
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Android implementation of [NetworkMonitor] using ConnectivityManager callbacks.
 *
 * Uses a companion object to hold the application context, initialized from Application.onCreate().
 */
actual class NetworkMonitor actual constructor() {

    companion object {
        private val logger = Logger("NetworkMonitor")
        private var applicationContext: Context? = null

        fun init(context: Context) {
            applicationContext = context.applicationContext
        }
    }

    private var lastLogged: String? = null

    /** isOnline is polled before every sync; log only when the verdict changes. */
    private fun logChange(message: String) {
        if (message != lastLogged) {
            lastLogged = message
            logger.info(message)
        }
    }

    private val connectivityManager: ConnectivityManager?
        get() = applicationContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    actual val isOnline: Boolean
        get() {
            val cm = connectivityManager
            if (cm == null) {
                logger.error("isOnline=false: no application context — NetworkMonitor.init() was never called")
                return false
            }
            val caps = try {
                cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            } catch (e: SecurityException) {
                logger.error("isOnline=false: missing ACCESS_NETWORK_STATE permission", e)
                return false
            }
            if (caps == null) {
                logChange("isOnline=false: no active network")
                return false
            }
            val internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            logChange("isOnline=${internet && validated} (internet=$internet validated=$validated)")
            return internet && validated
        }

    actual fun observeConnectivity(): Flow<Boolean> = callbackFlow {
        val cm = connectivityManager
        if (cm == null) {
            trySend(false)
            awaitClose()
            return@callbackFlow
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(true)
            }

            override fun onLost(network: Network) {
                trySend(false)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                val online = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                trySend(online)
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        // Emit current state first
        trySend(isOnline)

        cm.registerNetworkCallback(request, callback)
        awaitClose { cm.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}
