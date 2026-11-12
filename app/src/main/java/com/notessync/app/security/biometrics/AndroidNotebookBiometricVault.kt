package com.notessync.app.security.biometrics

import com.notessync.app.i18n.uiText

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** These are per-device runtime authentication keys, unrelated to APK signing. All disk/Keystore work is on IO. */
class AndroidNotebookBiometricVault(context: Context) : NotebookBiometricVault {
    private val app = context.applicationContext
    private val prefs by lazy { app.getSharedPreferences("notebook_biometrics", Context.MODE_PRIVATE) }
    private val writes = Mutex()
    private val _bindings = MutableStateFlow<Map<String, String>>(emptyMap())
    override val bindings = _bindings.asStateFlow()
    private var pruned = false
    private val records = mutableMapOf<String, Record>()
    private val json = Json { ignoreUnknownKeys = true }
    private val keys: KeyStore get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Serializable private data class Record(val alias: String, val tag: String, val iv: String, val data: String)

    override suspend fun load() = withContext(Dispatchers.IO) {
        writes.withLock {
            records.clear()
            val saved = prefs.all
            if (saved.isEmpty()) { publish(); return@withLock }
            runCatching {
                val store = keys
                saved.forEach { (id, raw) ->
                    val record = (raw as? String)?.takeIf { it.length <= 4096 }?.let { runCatching { json.decodeFromString<Record>(it) }.getOrNull() }
                    if (record != null && SAFE_ALIAS.matches(record.alias) && store.containsAlias(record.alias)) records[id] = record
                    else prefs.edit().remove(id).apply()
                }
                // Remove keys left by a process dying during enrollment, never other Keystore entries.
                prune(store)
            }
            publish()
        }
    }

    override suspend fun prepare(binding: NotebookBiometricBinding, enroll: Boolean): BiometricChallenge = withContext(Dispatchers.IO) {
        writes.withLock {
            val store = keys
            if (!pruned) prune(store)
            if (enroll) {
                val alias = PREFIX + UUID.randomUUID()
                try {
                    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                    generator.init(keySpec(alias))
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.ENCRYPT_MODE, generator.generateKey())
                    cipher.updateAAD(binding.credentialTag.toByteArray(Charsets.UTF_8))
                    BiometricChallenge(binding, true, cipher, alias)
                } catch (error: Exception) { store.deleteEntry(alias); throw error }
            } else {
                val record = records[binding.syncId] ?: error(uiText("Биометрия не включена"))
                check(record.tag == binding.credentialTag && SAFE_ALIAS.matches(record.alias))
                try {
                    val key = store.getKey(record.alias, null) as SecretKey
                    val iv = decode(record.iv); val data = decode(record.data)
                    check(iv.size == 12 && data.size in 16..128)
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                    cipher.updateAAD(binding.credentialTag.toByteArray(Charsets.UTF_8))
                    BiometricChallenge(binding, false, cipher, record.alias, data)
                } catch (error: Exception) { remove(binding.syncId, store); throw error }
            }
        }
    }

    override suspend fun verify(challenge: BiometricChallenge, cipher: Cipher): Boolean = withContext(Dispatchers.IO) {
        writes.withLock {
            if (!challenge.consume(cipher)) return@withLock false
            if (challenge.enroll) {
                val data = cipher.doFinal(MARKER)
                val record = Record(challenge.alias, challenge.binding.credentialTag, encode(cipher.iv), encode(data))
                val old = records[challenge.binding.syncId]
                check(prefs.edit().putString(challenge.binding.syncId, json.encodeToString(Record.serializer(), record)).commit())
                records[challenge.binding.syncId] = record
                publish()
                if (old != null && old.alias != record.alias && SAFE_ALIAS.matches(old.alias)) keys.deleteEntry(old.alias)
                true
            } else {
                val record = records[challenge.binding.syncId]
                if (record == null || record.alias != challenge.alias || record.tag != challenge.binding.credentialTag) false
                else try { java.security.MessageDigest.isEqual(MARKER, cipher.doFinal(challenge.ciphertext)) }
                catch (error: Exception) { remove(challenge.binding.syncId, keys); throw error }
            }
        }
    }

    override suspend fun discard(challenge: BiometricChallenge) = withContext(Dispatchers.IO) {
        if (challenge.enroll) writes.withLock {
            if (records[challenge.binding.syncId]?.alias == challenge.alias) remove(challenge.binding.syncId, keys)
            else if (SAFE_ALIAS.matches(challenge.alias)) keys.deleteEntry(challenge.alias)
        }
    }

    override suspend fun revoke(syncId: String) = withContext(Dispatchers.IO) { writes.withLock { remove(syncId, keys) } }
    private fun remove(id: String, store: KeyStore) {
        val record = records[id]
        check(prefs.edit().remove(id).commit())
        records.remove(id); publish()
        if (record != null && SAFE_ALIAS.matches(record.alias)) store.deleteEntry(record.alias)
    }
    private fun prune(store: KeyStore) {
        val referenced = records.values.map { it.alias }.toSet()
        store.aliases().toList().filter { SAFE_ALIAS.matches(it) && it !in referenced }.forEach { store.deleteEntry(it) }
        pruned = true
    }
    private fun publish() { _bindings.value = records.mapValues { it.value.tag } }
    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(value: String) = Base64.decode(value, Base64.NO_WRAP)
    internal fun keySpec(alias: String): KeyGenParameterSpec {
        require(SAFE_ALIAS.matches(alias))
        val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).setUserAuthenticationRequired(true).setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= 30) builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        else {
            @Suppress("DEPRECATION") // Required for per-use fingerprint authentication on Android26–29.
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        return builder.build()
    }
    companion object {
        private const val PREFIX = "notels.biometric."
        private val SAFE_ALIAS = Regex("notels\\.biometric\\.[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")
        private val MARKER = "NoteLS notebook biometric permission v1".toByteArray(Charsets.UTF_8)
    }
}
