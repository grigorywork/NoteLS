package com.notessync.app.ai

import android.app.Application
import android.util.Base64
import android.security.keystore.KeyProperties
import com.notessync.app.R
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** JCA encryption and key-provider fixtures, not a physical Android Keystore measurement. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class TransformSecretsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private class Keys : TransformKeyProvider {
        var available = true
        override fun key(create: Boolean): SecretKey {
            if (!available) throw IllegalStateException("unavailable fixture")
            return SecretKeySpec(ByteArray(32) { 17 }, "AES")
        }
        override fun clear() { available = false }
    }
    @Before fun before() { app.getSharedPreferences(AndroidTransformSecrets.PREFS, 0).edit().clear().commit() }
    @Test fun `ciphertext uses a new IV and authenticates tampering without storing plaintext`() = runBlocking {
        val keys = Keys(); val vault = AndroidTransformSecrets(app, keys); val prefs = app.getSharedPreferences(AndroidTransformSecrets.PREFS, 0)
        vault.save("fixture-token"); val first = prefs.getString("encrypted", null)!!; assertFalse(first.contains("fixture-token")); assertEquals("fixture-token", vault.read())
        vault.save("fixture-token"); val next = prefs.getString("encrypted", null)!!; assertNotEquals(first, next)
        val tampered = Base64.decode(next, Base64.NO_WRAP); tampered[tampered.lastIndex] = (tampered.last().toInt() xor 1).toByte()
        prefs.edit().putString("encrypted", Base64.encodeToString(tampered, Base64.NO_WRAP)).commit()
        assertEquals(TransformProblem.KEY_UNAVAILABLE, (runCatching { vault.read() }.exceptionOrNull() as TransformException).problem)
        vault.clear(); assertFalse(vault.present()); assertNull(vault.read())
    }
    @Test fun `unavailable platform key fails closed and rules exclude secret preferences from both OS backup paths`() = runBlocking {
        val keys = Keys().apply { available = false }; val vault = AndroidTransformSecrets(app, keys)
        assertTrue(runCatching { vault.save("fixture-token") }.isFailure); assertFalse(vault.present())
        val spec = AndroidTransformSecrets.spec()
        assertEquals(256, spec.keySize); assertTrue(spec.isRandomizedEncryptionRequired); assertFalse(spec.isUserAuthenticationRequired)
        assertArrayEquals(arrayOf(KeyProperties.BLOCK_MODE_GCM), spec.blockModes)
        for (resource in listOf(R.xml.backup_rules, R.xml.data_extraction_rules)) {
            var count = 0
            app.resources.getXml(resource).use { parser -> while (parser.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "exclude" && parser.getAttributeValue(null, "domain") == "sharedpref" && parser.getAttributeValue(null, "path") == "ai_secrets.xml") count++
                parser.next()
            } }
            assertEquals(if (resource == R.xml.backup_rules) 1 else 2, count)
        }
    }
}
