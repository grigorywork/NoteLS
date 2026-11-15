package com.notessync.app.ai

import android.app.Application
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

/** Fixture JCA keys only; not a claim of a physical device Keystore test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class GroqSecretsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private class Keys : TransformKeyProvider {
        var available = true
        override fun key(create: Boolean): SecretKey {
            check(available); return SecretKeySpec(ByteArray(32) { 17 }, "AES")
        }
        override fun clear() { available = false }
    }
    @Before fun before() {
        for (name in listOf(AndroidTransformSecrets.PREFS, AndroidTransformSecrets.GROQ_PREFS)) app.getSharedPreferences(name, 0).edit().clear().commit()
    }
    @Test fun `providers have separate credentials and deleting Groq preserves the existing OpenAI key`() = runBlocking {
        val open = AndroidTransformSecrets(app, Keys()); val groq = AndroidTransformSecrets(app, Keys(), AiProvider.GROQ_QWEN)
        open.save("fixture-openai"); assertFalse(groq.present()); assertNull(groq.read())
        groq.save("fixture-groq"); assertEquals("fixture-openai", open.read()); assertEquals("fixture-groq", groq.read())
        groq.clear(); assertFalse(groq.present()); assertEquals("fixture-openai", open.read()); assertTrue(open.present())
    }
    @Test fun `ciphertext copied across providers cannot decrypt even with identical fixture key bytes`() = runBlocking {
        val open = AndroidTransformSecrets(app, Keys()); val groq = AndroidTransformSecrets(app, Keys(), AiProvider.GROQ_QWEN)
        open.save("fixture-openai"); groq.save("fixture-groq")
        val a = app.getSharedPreferences(AndroidTransformSecrets.PREFS, 0); val b = app.getSharedPreferences(AndroidTransformSecrets.GROQ_PREFS, 0)
        val oldA = a.getString("encrypted", null); val oldB = b.getString("encrypted", null)
        b.edit().putString("encrypted", oldA).commit()
        assertEquals(TransformProblem.KEY_UNAVAILABLE, (runCatching { groq.read() }.exceptionOrNull() as TransformException).problem)
        a.edit().putString("encrypted", oldB).commit()
        assertEquals(TransformProblem.KEY_UNAVAILABLE, (runCatching { open.read() }.exceptionOrNull() as TransformException).problem)
    }
    @Test fun `distinct platform aliases and both backup paths exclude both provider vaults`() {
        assertNotEquals(AndroidTransformSecrets.ALIAS, AndroidTransformSecrets.GROQ_ALIAS)
        assertEquals(AndroidTransformSecrets.GROQ_ALIAS, AndroidTransformSecrets.spec(AndroidTransformSecrets.GROQ_ALIAS).keystoreAlias)
        for (resource in listOf(R.xml.backup_rules, R.xml.data_extraction_rules)) {
            val counts = mutableMapOf<String, Int>()
            app.resources.getXml(resource).use { parser -> while (parser.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "exclude" && parser.getAttributeValue(null, "domain") == "sharedpref") {
                    val path = parser.getAttributeValue(null, "path"); counts[path] = (counts[path] ?: 0) + 1
                }
                parser.next()
            } }
            for (name in listOf(AndroidTransformSecrets.PREFS, AndroidTransformSecrets.GROQ_PREFS)) assertEquals(if (resource == R.xml.backup_rules) 1 else 2, counts["$name.xml"] ?: 0)
        }
    }
}
