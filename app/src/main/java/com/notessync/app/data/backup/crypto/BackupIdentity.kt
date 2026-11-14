package com.notessync.app.data.backup.crypto

import kotlinx.serialization.Serializable
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/** Public encryption identity plus a password-encrypted private key; no password is persisted. */
@Serializable
internal data class BackupIdentity(val publicKey: String, val salt: String, val rounds: Int,
    val privateIv: String, val privateData: String) {
    val id: String get() = hex(MessageDigest.getInstance("SHA-256").digest(decode(publicKey)))
    fun validate() {
        require(rounds in 100_000..1_000_000)
        require(decode(salt).size == 16 && decode(privateIv).size == 12)
        require(decode(privateData).size in 512..4096)
        public()
    }
    fun public(): PublicKey {
        val bytes = decode(publicKey); require(bytes.size in 256..512)
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(bytes)) as RSAPublicKey
        require(key.modulus.bitLength() == 2048 && key.publicExponent == java.math.BigInteger.valueOf(65537))
        return key
    }
    fun unlock(password: CharArray): BackupAccess {
        validate(); require(password.size in 1..256)
        val plain = passwordCipher(password, false).doFinal(decode(privateData))
        try { return access(plain) } finally { plain.fill(0) }
    }
    fun access(plain: ByteArray): BackupAccess {
        require(plain.size in 512..4096)
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(plain)) as RSAPrivateKey
        val pub = public() as RSAPublicKey
        require(key.modulus == pub.modulus)
        // Android providers need not expose CRT fields. Prove the pair with a fresh OAEP challenge.
        val challenge = random(32)
        var checked: ByteArray? = null
        try {
            checked = unwrap(key, wrap(pub, challenge))
            require(MessageDigest.isEqual(challenge, checked))
            return BackupAccess(id, key)
        } finally { challenge.fill(0); checked?.fill(0) }
    }
    fun changePassword(access: BackupAccess, password: CharArray): BackupIdentity {
        require(password.size in 8..256 && access.keyId == id)
        // Old portable archives expose this private key to anyone with the old password.
        // Rewrapping it would let that key decrypt every future archive as well.
        // Prove that the authorized access is still live before generating a new recipient.
        access.encoded().fill(0)
        return create(password)
    }
    private fun passwordCipher(password: CharArray, encrypt: Boolean): Cipher {
        val spec = PBEKeySpec(password, decode(salt), rounds, 256)
        val bytes = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
            finally { spec.clearPassword() }
        try { return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(bytes, "AES"), GCMParameterSpec(128, decode(privateIv)))
            updateAAD(("NoteLS backup identity v1|$publicKey|$salt|$rounds").toByteArray(Charsets.UTF_8))
        } } finally { bytes.fill(0) }
    }
    override fun toString() = "BackupIdentity(encrypted)"
    companion object {
        const val DEFAULT_ROUNDS = 600_000
        fun create(password: CharArray, rounds: Int = DEFAULT_ROUNDS): BackupIdentity {
            require(password.size in 8..256 && rounds in 100_000..1_000_000)
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val fresh = BackupIdentity(encode(pair.public.encoded), encode(random(16)), rounds, encode(random(12)), "")
            val plain = pair.private.encoded
            try { return fresh.copy(privateData = encode(fresh.passwordCipher(password, true).doFinal(plain))) }
            finally { plain.fill(0) }
        }
        fun wrap(key: PublicKey, bytes: ByteArray): ByteArray = rsa(Cipher.ENCRYPT_MODE, key).doFinal(bytes)
        internal fun unwrap(key: PrivateKey, bytes: ByteArray): ByteArray = rsa(Cipher.DECRYPT_MODE, key).doFinal(bytes)
        private fun rsa(mode: Int, key: java.security.Key) = Cipher.getInstance("RSA/ECB/OAEPPadding").apply {
            init(mode, key, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT))
        }
        internal fun random(size: Int) = ByteArray(size).also { SecureRandom().nextBytes(it) }
        internal fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
        internal fun decode(value: String): ByteArray { require(value.length <= 8192); return Base64.getDecoder().decode(value) }
        internal fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    }
}

/** Ephemeral unlock, never SavedState/preferences/sync/backup metadata. */
class BackupAccess internal constructor(val keyId: String, key: PrivateKey) : AutoCloseable {
    private var key: PrivateKey? = key
    internal fun unwrap(bytes: ByteArray): ByteArray = BackupIdentity.unwrap(checkNotNull(key), bytes)
    internal fun encoded(): ByteArray = checkNotNull(key).encoded
    override fun close() { key = null }
    override fun toString() = "BackupAccess(redacted)"
}
