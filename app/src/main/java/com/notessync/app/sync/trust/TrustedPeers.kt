package com.notessync.app.sync.trust

import com.notessync.app.sync.crypto.PairingSecret
import com.notessync.app.sync.qr.SyncQr
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/** UI never receives credentials or their serialization. */
data class PeerSummary(val id: String, val name: String, val lastSyncAt: Long, val revision: String)
data class TrustState(val ready: Boolean = false, val available: Boolean = true, val peers: List<PeerSummary> = emptyList())

@Serializable
internal data class TrustedPeer(
    val id: String, val name: String, val secret: String,
    val lastSyncAt: Long = 0, val host: String = "", val revision: String
) {
    override fun toString() = "TrustedPeer($id, credential=redacted)"
    fun summary() = PeerSummary(id, name, lastSyncAt, revision)
    fun valid() = TrustIds.valid(id) && name.length <= 128 && PairingSecret.valid(secret) &&
        lastSyncAt >= 0 && (host.isEmpty() || SyncQr.localAddress(host)) && TrustIds.valid(revision)
}
internal object TrustIds {
    private val uuid = Regex("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")
    fun valid(value: String) = uuid.matches(value)
}
internal interface TrustedPeers {
    val state: StateFlow<TrustState>
    suspend fun load()
    suspend fun get(id: String): TrustedPeer?
    suspend fun pair(id: String, name: String, secret: String, host: String, watermark: Long)
    suspend fun synced(id: String, revision: String, watermark: Long, host: String)
    suspend fun forget(id: String)
    suspend fun reset()
    fun current(id: String, revision: String): Boolean = state.value.available && state.value.peers.any { it.id == id && it.revision == revision }
}
