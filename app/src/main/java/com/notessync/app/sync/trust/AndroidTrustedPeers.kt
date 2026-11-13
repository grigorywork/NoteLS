package com.notessync.app.sync.trust

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Separate device-bound key, unrelated to app signing, API credentials or biometric keys. */
internal interface TrustKeyProvider { fun key(create: Boolean): SecretKey; fun clear() }
private class AndroidTrustKeys : TrustKeyProvider {
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    override fun key(create: Boolean): SecretKey {
        val old = store().getKey(AndroidTrustedPeers.ALIAS, null) as? SecretKey
        if (old != null) return old
        check(create) { "Trust key unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(AndroidTrustedPeers.spec())
        }.generateKey()
    }
    override fun clear() { store().deleteEntry(AndroidTrustedPeers.ALIAS) }
}
internal class AndroidTrustedPeers(context: Context, private val keys: TrustKeyProvider = AndroidTrustKeys()) : TrustedPeers {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(TrustState())
    override val state = mutable.asStateFlow()
    private val serializer = ListSerializer(TrustedPeer.serializer())
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }

    override suspend fun load() = withContext(Dispatchers.IO) { mutex.withLock {
        try { publish(read()) } catch (_: Exception) { mutable.value = TrustState(true, false) }
    } }
    override suspend fun get(id: String): TrustedPeer? = withContext(Dispatchers.IO) { mutex.withLock {
        check(mutable.value.available)
        try { read().singleOrNull { it.id == id } }
        catch (error: Exception) { mutable.value = TrustState(true, false); throw error }
    } }
    override suspend fun pair(id: String, name: String, secret: String, host: String, watermark: Long) = change { peers ->
        val peer = TrustedPeer(id, name.take(128), secret, watermark,
            host.takeIf(com.notessync.app.sync.qr.SyncQr::localAddress).orEmpty(), UUID.randomUUID().toString())
        require(peer.valid()); require(peers.any { it.id == id } || peers.size < MAX_PEERS)
        peers.filterNot { it.id == id } + peer
    }
    override suspend fun synced(id: String, revision: String, watermark: Long, host: String) = change { peers ->
        peers.map { if (it.id == id && it.revision == revision) it.copy(lastSyncAt = maxOf(it.lastSyncAt, watermark),
            host = host.takeIf(com.notessync.app.sync.qr.SyncQr::localAddress) ?: it.host) else it }
    }
    override suspend fun forget(id: String) = change { it.filterNot { peer -> peer.id == id } }
    override suspend fun reset() = withContext(Dispatchers.IO) { mutex.withLock {
        check(prefs.edit().clear().commit()); runCatching { keys.clear() }; publish(emptyList())
    } }
    private suspend fun change(update: (List<TrustedPeer>) -> List<TrustedPeer>) = withContext(Dispatchers.IO) { mutex.withLock {
        check(mutable.value.available)
        val old = try { read() } catch (error: Exception) { mutable.value = TrustState(true, false); throw error }
        run {
            val peers = update(old); require(peers.size <= MAX_PEERS && peers.all { it.valid() } && peers.map { it.id }.distinct().size == peers.size)
            if (peers.isEmpty()) { check(prefs.edit().clear().commit()); publish(peers); return@run }
            val plain = json.encodeToString(serializer, peers).toByteArray(Charsets.UTF_8)
            try {
                require(plain.size <= MAX_BYTES)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, keys.key(true)) }
                require(cipher.iv.size == 12)
                cipher.updateAAD(AAD)
                val packed = cipher.iv + cipher.doFinal(plain)
                check(prefs.edit().putString("encrypted", Base64.encodeToString(packed, Base64.NO_WRAP)).commit())
            } finally { plain.fill(0) }
            publish(peers)
        }
    } }
    private fun read(): List<TrustedPeer> {
        val packed = prefs.getString("encrypted", null) ?: return emptyList()
        require(packed.length <= 90_000)
        val bytes = Base64.decode(packed, Base64.NO_WRAP); require(bytes.size in 29..(MAX_BYTES + 28))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, keys.key(false), GCMParameterSpec(128, bytes.copyOfRange(0, 12))); updateAAD(AAD)
        }
        val plain = cipher.doFinal(bytes, 12, bytes.size - 12)
        try {
            return json.decodeFromString(serializer, plain.toString(Charsets.UTF_8)).also { peers ->
                require(peers.size <= MAX_PEERS && peers.all { it.valid() } && peers.map { it.id }.distinct().size == peers.size)
            }
        } finally { plain.fill(0) }
    }
    private fun publish(peers: List<TrustedPeer>) { mutable.value = TrustState(true, true, peers.map { it.summary() }.sortedBy { it.name }) }
    companion object {
        const val PREFS = "sync_trust"
        const val ALIAS = "notels.sync.trust.v1"
        private const val MAX_BYTES = 64 * 1024
        private const val MAX_PEERS = 32
        private val AAD = "NoteLS trusted peers v1".toByteArray(Charsets.US_ASCII)
        internal fun spec() = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).setRandomizedEncryptionRequired(true).build()
    }
}
