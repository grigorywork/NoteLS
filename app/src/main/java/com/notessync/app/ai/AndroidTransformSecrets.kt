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

private class AndroidTransformKeys(private val alias: String) : TransformKeyProvider {
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    override fun key(create: Boolean): SecretKey {
        val old = store().getKey(alias, null) as? SecretKey
        if (old != null) return old
        if (!create) throw TransformException(TransformProblem.KEY_UNAVAILABLE)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(AndroidTransformSecrets.spec(alias)) }.generateKey()
    }
    override fun clear() { store().deleteEntry(alias) }
}
class AndroidTransformSecrets(context: Context, keys: TransformKeyProvider? = null, provider: AiProvider = AiProvider.OPENAI) : TransformSecrets {
    private val alias = when (provider) {
        AiProvider.OPENAI -> ALIAS
        AiProvider.GROQ_QWEN -> GROQ_ALIAS
        AiProvider.LOCAL_RULES -> error("Local rules do not use credentials")
    }
    private val keys = keys ?: AndroidTransformKeys(alias)
    private val aad = if (provider == AiProvider.GROQ_QWEN) alias.toByteArray(Charsets.UTF_8) else null
    private val prefs = context.applicationContext.getSharedPreferences(prefsName(provider), Context.MODE_PRIVATE)
    override suspend fun present(): Boolean = withContext(Dispatchers.IO) { prefs.contains("encrypted") }
    override suspend fun save(value: String) = withContext(Dispatchers.IO) {
        val secret = value.trim()
        if (!TransformLimits.validKey(secret)) throw TransformException(TransformProblem.KEY_UNAVAILABLE)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, keys.key(true)) }
            require(cipher.iv.size == 12)
            aad?.let(cipher::updateAAD)
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
            aad?.let(cipher::updateAAD)
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
        const val GROQ_PREFS = "ai_secrets_groq"
        internal const val GROQ_ALIAS = "notels.ai.groq.qwen.v1"
        internal fun prefsName(provider: AiProvider) = when (provider) {
            AiProvider.OPENAI -> PREFS
            AiProvider.GROQ_QWEN -> GROQ_PREFS
            AiProvider.LOCAL_RULES -> error("Local rules do not use credentials")
        }
        internal fun spec(alias: String = ALIAS) = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).setRandomizedEncryptionRequired(true).build()
    }
}
