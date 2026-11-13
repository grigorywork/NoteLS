package com.notessync.app.sync.auto

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.notessync.app.sync.qr.SyncQr
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.net.Inet4Address

internal data class WifiLink(val network: Network, val address: String)
internal fun wifiLink(network: Network, capabilities: NetworkCapabilities?, properties: LinkProperties?): WifiLink? {
    if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return null
    val address = properties?.linkAddresses?.map { it.address }?.filterIsInstance<Inet4Address>()
        ?.firstOrNull { SyncQr.localAddress(it.hostAddress.orEmpty()) }?.hostAddress ?: return null
    return WifiLink(network, address)
}
internal fun wifiLinks(context: Context) = callbackFlow<WifiLink?> {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    data class Entry(var capabilities: NetworkCapabilities? = null, var properties: LinkProperties? = null)
    val lock = Any(); val entries = linkedMapOf<Network, Entry>()
    fun publish() { trySend(entries.entries.firstNotNullOfOrNull { (network, entry) -> wifiLink(network, entry.capabilities, entry.properties) }) }
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { synchronized(lock) { entries.getOrPut(network) { Entry() }; publish() } }
        override fun onLost(network: Network) { synchronized(lock) { entries.remove(network); publish() } }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            synchronized(lock) { entries[network]?.capabilities = capabilities; publish() }
        }
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            synchronized(lock) { entries[network]?.properties = properties; publish() }
        }
    }
    // API26+ supplies capabilities and link properties after onAvailable. No polling/binder re-query race.
    manager.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback)
    trySend(null)
    awaitClose { runCatching { manager.unregisterNetworkCallback(callback) }; synchronized(lock) { entries.clear() } }
}.distinctUntilChanged()
