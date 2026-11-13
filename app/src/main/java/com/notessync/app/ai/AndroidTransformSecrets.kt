package com.notessync.app.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** User API credential only; never app signing material. No plaintext or software fallback. */
interface TransformKeyProvider { fun key(create: Boolean): SecretKey; fun clear() }

private class AndroidTransformKeys : TransformKeyProvider {
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    override fun key(create: Boolean): SecretKey {
        val old = store().getKey(AndroidTransformSecrets.ALIAS, null) as? SecretKey
        if (old != null) return old
        if (!create) throw TransformException(TransformProblem.KEY_UNAVAILABLE)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(AndroidTransformSecrets.spec()) }.generateKey()
    }
    override fun clear() { store().deleteEntry(AndroidTransformSecrets.ALIAS) }
}
class AndroidTransformSecrets(context: Context, private val keys: TransformKeyProvider = AndroidTransformKeys()) : TransformSecrets {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    override suspend fun present(): Boolean = withContext(Dispatchers.IO) { prefs.contains("encrypted") }
    override suspend fun save(value: String) = withContext(Dispatchers.IO) {
        val secret = value.trim()
        if (!TransformLimits.validKey(secret)) throw TransformException(TransformProblem.KEY_UNAVAILABLE)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, keys.key(true)) }
            require(cipher.iv.size == 12)
            val packed = cipher.iv + cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
            check(prefs.edit().putString("encrypted", Base64.encodeToString(packed, Base64.NO_WRAP)).commit())
        } catch (_: Exception) { throw TransformException(TransformProblem.KEY_UNAVAILABLE) }
        Unit
    }
    override suspend fun read(): String? = withContext(Dispatchers.IO) {
        try {
            val raw = prefs.getString("encrypted", null) ?: return@withContext null
            require(raw.length <= 12_000)
            val bytes = Base64.decode(raw, Base64.NO_WRAP); require(bytes.size >= 29)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, keys.key(false), GCMParameterSpec(128, bytes.copyOfRange(0, 12))) }
            String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8).also { require(TransformLimits.validKey(it)) }
        } catch (_: Exception) { throw TransformException(TransformProblem.KEY_UNAVAILABLE) }
    }
    override suspend fun clear() = withContext(Dispatchers.IO) {
        check(prefs.edit().clear().commit())
        runCatching { keys.clear() }
        Unit
    }
    companion object {
        const val PREFS = "ai_secrets"
        internal const val ALIAS = "notels.ai.openai.v1"
        internal fun spec() = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).setRandomizedEncryptionRequired(true).build()
    }
}
