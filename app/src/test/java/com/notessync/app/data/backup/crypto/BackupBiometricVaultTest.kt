package com.notessync.app.data.backup.crypto

import android.app.Application
import android.security.keystore.KeyProperties
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlinx.serialization.json.*
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Software injected keys exercise ownership/ciphertext only; no fingerprint sensor is simulated as real hardware. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, manifest = Config.NONE)
class BackupBiometricVaultTest {
    private class SoftwareKeys : BackupBiometricKeys {
        val values = mutableMapOf<String, SecretKey>()
        override fun create(alias: String) = KeyGenerator.getInstance("AES").run { init(256); generateKey() }.also { values[alias] = it }
        override fun get(alias: String) = values.getValue(alias)
        override fun remove(alias: String) { values.remove(alias) }
        override fun prune(keep: String?) { values.keys.retainAll(setOfNotNull(keep)) }
    }
    private val keys = SoftwareKeys()
    private val vault get() = BackupBiometricVault(RuntimeEnvironment.getApplication(), keys)
    private val identity by lazy { BackupIdentity.create("test password".toCharArray(), 100000) }
    private suspend fun enroll(v: BackupBiometricVault): BackupBiometricVault.Challenge {
        val pending = identity.unlock("test password".toCharArray()).use { v.prepare(identity, it) }
        assertNull(v.complete(pending, pending.cipher)); v.discard(pending); return pending
    }
    @Test fun `matching authorized cipher unwraps actual private key once and password remains independent`() = runBlocking {
        val v = vault; val enrolled = enroll(v)
        assertTrue(v.enabled(identity.id)); assertTrue(keys.values.containsKey(enrolled.alias))
        val pending = v.prepare(identity)
        assertTrue(runCatching { v.complete(pending, Cipher.getInstance("AES/GCM/NoPadding")) }.isFailure)
        v.complete(pending, pending.cipher)!!.use { lease ->
            assertEquals(identity.id, lease.keyId)
            val secret = ByteArray(32) { 7 }
            assertArrayEquals(secret, lease.unwrap(BackupIdentity.wrap(identity.public(), secret)))
        }
        assertTrue(runCatching { v.complete(pending, pending.cipher) }.isFailure)
        v.discard(pending)
        v.disable(); assertTrue(keys.values.isEmpty()); assertFalse(v.enabled(identity.id))
        identity.unlock("test password".toCharArray()).close()
    }
    @Test fun `cancelled enrollment removes temporary key and a cancelled decrypt grants nothing`() = runBlocking {
        val v = vault
        val pending = identity.unlock("test password".toCharArray()).use { v.prepare(identity, it) }
        v.discard(pending); assertTrue(keys.values.isEmpty()); assertTrue(pending.bytes.all { it == 0.toByte() })
        assertTrue(runCatching { v.complete(pending, pending.cipher) }.isFailure)
        enroll(v); val unlock = v.prepare(identity); v.discard(unlock)
        assertTrue(runCatching { v.complete(unlock, unlock.cipher) }.isFailure)
    }
    @Test fun `lost keystore key and different identity fail without damaging portable password unlock`() = runBlocking {
        val v = vault; enroll(v); keys.values.clear()
        assertTrue(runCatching { v.prepare(identity) }.isFailure)
        val other = BackupIdentity.create("test password".toCharArray(), 100000)
        assertFalse(v.enabled(other.id)); assertTrue(runCatching { v.prepare(other) }.isFailure)
        identity.unlock("test password".toCharArray()).close()
    }
    @Test fun `corrupt metadata cannot unlock and password authorized enrollment can replace it`() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("backup_biometrics", 0).edit().putString("key", "{invalid").commit()
        val v = vault
        assertFalse(v.enabled(identity.id)); assertTrue(runCatching { v.prepare(identity) }.isFailure)
        enroll(v); assertTrue(v.enabled(identity.id))
        v.prepare(identity).let { pending -> v.complete(pending, pending.cipher)!!.close(); v.discard(pending) }
    }
    @Test fun `tampered biometric ciphertext grants no access and leaves password usable`() = runBlocking {
        val v = vault; enroll(v)
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("backup_biometrics", 0)
        val record = Json.parseToJsonElement(prefs.getString("key", null)!!).jsonObject.toMutableMap()
        val bytes = BackupIdentity.decode(record.getValue("data").jsonPrimitive.content)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        record["data"] = JsonPrimitive(BackupIdentity.encode(bytes))
        prefs.edit().putString("key", JsonObject(record).toString()).commit()
        val pending = v.prepare(identity)
        assertTrue(runCatching { v.complete(pending, pending.cipher) }.isFailure); v.discard(pending)
        identity.unlock("test password".toCharArray()).close()
    }
    @Test fun `key policy requires per use strong biometric and invalidates on fingerprint enrollment`() {
        val spec = AndroidBackupBiometricKeys().spec(AndroidBackupBiometricKeys.PREFIX + "11111111-1111-1111-1111-111111111111")
        assertTrue(spec.isUserAuthenticationRequired); assertEquals(0, spec.userAuthenticationValidityDurationSeconds)
        assertEquals(KeyProperties.AUTH_BIOMETRIC_STRONG, spec.userAuthenticationType)
        assertTrue(spec.isInvalidatedByBiometricEnrollment); assertEquals(256, spec.keySize)
        assertTrue(runCatching { AndroidBackupBiometricKeys().spec("release") }.isFailure)
    }
    @Test @Config(sdk = [28]) fun `old Android requires fresh fingerprint for every use`() {
        val spec = AndroidBackupBiometricKeys().spec(AndroidBackupBiometricKeys.PREFIX + "11111111-1111-1111-1111-111111111111")
        assertTrue(spec.isUserAuthenticationRequired); assertEquals(-1, spec.userAuthenticationValidityDurationSeconds)
    }
}
