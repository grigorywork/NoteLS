package com.notessync.app.sync.auto

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.notessync.app.sync.qr.SyncQr
import com.notessync.app.sync.trust.TrustIds
import java.io.Closeable
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.coroutines.resume

internal class AndroidAutoNetwork(context: Context, private val link: WifiLink) : AutoNetwork {
    private val nsd = context.getSystemService(NsdManager::class.java)
    override val address = link.address
    override fun socket(): Socket = link.network.socketFactory.createSocket()
    override fun listen(): ServerSocket {
        val server = ServerSocket()
        try { server.bind(InetSocketAddress(address, AUTO_PORT)); return server }
        catch (error: Exception) { server.close(); throw error }
    }
    override fun discover(ownId: String, allowed: Set<String>, found: (PeerEndpoint) -> Unit, lost: (String) -> Unit): Closeable {
        val closed = AtomicBoolean(false)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val queue = Channel<NsdServiceInfo>(8)
        val pending = ConcurrentHashMap.newKeySet<String>()
        val resolved = ConcurrentHashMap<String, String>()
        val registration = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onServiceRegistered(info: NsdServiceInfo) { if (closed.get()) runCatching { nsd.unregisterService(this) } }
        }
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) { if (closed.get()) runCatching { nsd.stopServiceDiscovery(this) } }
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(type: String, errorCode: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                if (closed.get() || info.serviceType.trimEnd('.') != TYPE.trimEnd('.') || info.serviceName.length > 63) return
                if (allowed.none { info.serviceName == "NoteLS-$it" || info.serviceName.startsWith("NoteLS-$it (") }) return
                if (resolved.size >= 64 || pending.size >= 8 || !pending.add(info.serviceName)) return
                if (queue.trySend(info).isFailure) pending.remove(info.serviceName)
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                val id = resolved.remove(info.serviceName) ?: return
                if (!closed.get() && id !in resolved.values) lost(id)
            }
        }
        val close = Closeable {
            if (closed.compareAndSet(false, true)) {
                scope.cancel(); queue.close()
                runCatching { nsd.stopServiceDiscovery(discovery) }
                runCatching { nsd.unregisterService(registration) }
                pending.clear(); resolved.clear()
            }
        }
        try {
            nsd.registerService(NsdServiceInfo().apply {
                serviceName = "NoteLS-$ownId"; serviceType = TYPE; port = AUTO_PORT
                setAttribute("id", ownId); setAttribute("v", "1")
            }, NsdManager.PROTOCOL_DNS_SD, registration)
            nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
            scope.launch {
                for (info in queue) {
                    try {
                        val endpoint = withTimeoutOrNull(5_000) { resolve(info, allowed) }
                        if (endpoint != null && !closed.get()) { resolved[info.serviceName] = endpoint.id; found(endpoint) }
                    } finally { pending.remove(info.serviceName) }
                }
            }
        } catch (error: Exception) { close.close(); throw error }
        return close
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(info: NsdServiceInfo, allowed: Set<String>): PeerEndpoint? = suspendCancellableCoroutine { continuation ->
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(service: NsdServiceInfo, errorCode: Int) { if (continuation.isActive) continuation.resume(null) }
            override fun onServiceResolved(service: NsdServiceInfo) {
                if (!continuation.isActive) return
                val idBytes = service.attributes["id"]
                val id = if (idBytes?.size == 36) idBytes.toString(Charsets.UTF_8) else ""
                val version = service.attributes["v"]?.takeIf { it.size == 1 }?.toString(Charsets.US_ASCII)
                val addresses = if (Build.VERSION.SDK_INT >= 34) service.hostAddresses else listOfNotNull(service.host)
                val host = addresses.filterIsInstance<Inet4Address>().firstOrNull { SyncQr.localAddress(it.hostAddress.orEmpty()) }?.hostAddress
                val sameNetwork = Build.VERSION.SDK_INT < 33 || service.network == null || service.network == link.network
                val endpoint = if (sameNetwork && TrustIds.valid(id) && id in allowed && version == "1" && host != null && service.port == AUTO_PORT)
                    PeerEndpoint(id, host) else null
                continuation.resume(endpoint)
            }
        }
        continuation.invokeOnCancellation { if (Build.VERSION.SDK_INT >= 34) runCatching { nsd.stopServiceResolution(listener) } }
        try { nsd.resolveService(info, listener) }
        catch (_: Exception) { if (continuation.isActive) continuation.resume(null) }
    }
    companion object { private const val TYPE = "_notels._tcp." }
}
