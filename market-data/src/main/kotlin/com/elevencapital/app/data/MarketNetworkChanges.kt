package com.elevencapital.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/** A connectivity change accelerates recovery, but never gates loopback/USB preview traffic. */
fun marketNetworkChanges(context: Context): Flow<Unit> = callbackFlow {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    var previous = manager.activeNetwork
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (previous != network) {
                previous = network
                trySend(Unit)
            }
        }
        override fun onLost(network: Network) {
            if (previous == network) {
                previous = null
                trySend(Unit)
            }
        }
    }
    manager.registerDefaultNetworkCallback(callback)
    awaitClose { manager.unregisterNetworkCallback(callback) }
}.conflate()
