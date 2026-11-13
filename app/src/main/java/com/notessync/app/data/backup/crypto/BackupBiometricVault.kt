package com.notessync.app.data.backup.crypto

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/** Local private key wrapping. Only the exact, once-authorized CryptoObject can release its bytes. */
internal class BackupBiometricVault(context: Context, private val keys: BackupBiometricKeys = AndroidBackupBiometricKeys()) {
    private val app = context.applicationContext
    private val prefs by lazy { app.getSharedPreferences("backup_biometrics", Context.MODE_PRIVATE) }
    private val lock = Mutex()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    @Serializable private data class Record(val id: String, val alias: String, val iv: String, val data: String)
    private fun record(): Record? = runCatching { prefs.getString("key", null)?.let { raw ->
        require(raw.length <= 8192)
        json.decodeFromString<Record>(raw).also {
            require(it.id.matches(Regex("[0-9a-f]{64}")) && AndroidBackupBiometricKeys.safe(it.alias))
            require(BackupIdentity.decode(it.iv).size == 12 && BackupIdentity.decode(it.data).size in 528..4112)
        }
    } }.getOrNull() // Corrupt local biometric metadata grants nothing; password-authorized enrollment can replace it.
    suspend fun enabled(id: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { record()?.id == id }.getOrDefault(false)
    }
    suspend fun prepare(identity: BackupIdentity, access: BackupAccess? = null): Challenge = withContext(Dispatchers.IO) {
        lock.withLock {
            identity.validate()
            val old = record(); keys.prune(old?.alias)
            if (access != null) {
                require(access.keyId == identity.id)
                val alias = AndroidBackupBiometricKeys.PREFIX + UUID.randomUUID()
                var plain: ByteArray? = null
                try {
                    plain = access.encoded()
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                        init(Cipher.ENCRYPT_MODE, keys.create(alias)); updateAAD(identity.id.toByteArray(Charsets.US_ASCII))
                    }
                    Challenge(identity, alias, cipher, true, plain)
                } catch (error: Throwable) { plain?.fill(0); runCatching { keys.remove(alias) }; throw error }
            } else {
                val stored = requireNotNull(old); require(stored.id == identity.id)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, keys.get(stored.alias), GCMParameterSpec(128, BackupIdentity.decode(stored.iv)))
                    updateAAD(identity.id.toByteArray(Charsets.US_ASCII))
                }
                Challenge(identity, stored.alias, cipher, false, BackupIdentity.decode(stored.data))
            }
        }
    }
    suspend fun complete(challenge: Challenge, authorized: Cipher): BackupAccess? = withContext(Dispatchers.IO) {
        lock.withLock {
            check(challenge.consume(authorized))
            try {
                if (challenge.enroll) {
                    val old = record()
                    val data = authorized.doFinal(challenge.bytes)
                    val next = Record(challenge.identity.id, challenge.alias, BackupIdentity.encode(authorized.iv), BackupIdentity.encode(data))
                    check(prefs.edit().putString("key", json.encodeToString(Record.serializer(), next)).commit())
                    challenge.saved = true
                    if (old != null && old.alias != next.alias) runCatching { keys.remove(old.alias) }
                    null
                } else {
                    val stored = requireNotNull(record()); check(stored.id == challenge.identity.id && stored.alias == challenge.alias)
                    val plain = authorized.doFinal(challenge.bytes)
                    try { challenge.identity.access(plain) } finally { plain.fill(0) }
                }
            } finally { challenge.bytes.fill(0) }
        }
    }
    suspend fun discard(challenge: Challenge) = withContext(Dispatchers.IO) {
        lock.withLock {
            challenge.invalidate()
            if (challenge.enroll && !challenge.saved) runCatching { keys.remove(challenge.alias) }
        }
    }
    suspend fun disable() = withContext(Dispatchers.IO) {
        lock.withLock {
            val old = record()
            check(prefs.edit().clear().commit())
            if (old != null) runCatching { keys.remove(old.alias) }
        }
    }
    internal class Challenge(val identity: BackupIdentity, val alias: String, val cipher: Cipher,
        val enroll: Boolean, internal val bytes: ByteArray) {
        private val consumed = AtomicBoolean(false)
        internal var saved = false
        fun consume(value: Cipher) = value === cipher && consumed.compareAndSet(false, true)
        fun invalidate() { consumed.set(true); bytes.fill(0) }
        override fun toString() = "BackupBiometricChallenge(redacted)"
    }
}
