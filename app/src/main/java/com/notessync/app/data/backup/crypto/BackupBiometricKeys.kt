package com.notessync.app.data.backup.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

internal interface BackupBiometricKeys {
    fun create(alias: String): SecretKey
    fun get(alias: String): SecretKey
    fun remove(alias: String)
    fun prune(keep: String?)
}

/** Per-use strong biometric keys, unrelated to APK signing or notebook authentication. */
internal class AndroidBackupBiometricKeys : BackupBiometricKeys {
    private val store get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    override fun create(alias: String): SecretKey = KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
        init(spec(alias)); generateKey()
    }
    override fun get(alias: String) = store.getKey(alias, null) as SecretKey
    override fun remove(alias: String) { require(safe(alias)); store.deleteEntry(alias) }
    override fun prune(keep: String?) {
        store.aliases().toList().filter { safe(it) && it != keep }.forEach(::remove)
    }
    internal fun spec(alias: String): KeyGenParameterSpec {
        require(safe(alias))
        val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true).setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= 30) builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        else {
            @Suppress("DEPRECATION") // Per-use fingerprint authentication on Android26–29.
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        return builder.build()
    }
    companion object {
        const val PREFIX = "notels.backup.bio."
        fun safe(alias: String) = Regex("notels\\.backup\\.bio\\.[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}").matches(alias)
    }
}
