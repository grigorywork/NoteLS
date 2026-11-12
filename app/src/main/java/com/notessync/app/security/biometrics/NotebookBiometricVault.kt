package com.notessync.app.security.biometrics

import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher

/** Android crypto/prompt boundary; the access policy and password binding have no Android APIs. */
interface NotebookBiometricVault {
    val bindings: StateFlow<Map<String, String>>
    suspend fun load()
    suspend fun prepare(binding: NotebookBiometricBinding, enroll: Boolean): BiometricChallenge
    suspend fun verify(challenge: BiometricChallenge, cipher: Cipher): Boolean
    suspend fun discard(challenge: BiometricChallenge)
    suspend fun revoke(syncId: String)
}

class BiometricChallenge(
    val binding: NotebookBiometricBinding,
    val enroll: Boolean,
    val cipher: Cipher,
    internal val alias: String,
    internal val ciphertext: ByteArray = byteArrayOf()
) {
    private val used = AtomicBoolean(false)
    internal fun consume(authenticated: Cipher): Boolean = used.compareAndSet(false, true) && cipher === authenticated
}
