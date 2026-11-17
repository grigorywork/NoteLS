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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class FreeModelsSecretsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val providers = listOf(AiProvider.OPENAI, AiProvider.GROQ_QWEN, AiProvider.GEMINI_FREE, AiProvider.OPENROUTER_GEMMA)
    private class Keys : TransformKeyProvider {
        override fun key(create: Boolean): SecretKey = SecretKeySpec(ByteArray(32) { 29 }, "AES")
        override fun clear() = Unit
    }
    @Before fun before() {
        for (provider in providers) app.getSharedPreferences(AndroidTransformSecrets.prefsName(provider), 0).edit().clear().commit()
    }
    @Test fun `service credentials are isolated while the two OpenRouter models share only their own vault`() = runBlocking {
        val vaults = providers.associateWith { AndroidTransformSecrets(app, Keys(), it) }
        for ((provider, vault) in vaults) vault.save("fixture-" + provider.name)
        for ((provider, vault) in vaults) assertEquals("fixture-" + provider.name, vault.read())
        val secondRouter = AndroidTransformSecrets(app, Keys(), AiProvider.OPENROUTER_NEMOTRON)
        assertEquals(vaults.getValue(AiProvider.OPENROUTER_GEMMA).read(), secondRouter.read())
        vaults.getValue(AiProvider.GEMINI_FREE).clear()
        assertNull(vaults.getValue(AiProvider.GEMINI_FREE).read())
        for (provider in providers.filter { it != AiProvider.GEMINI_FREE }) assertEquals("fixture-" + provider.name, vaults.getValue(provider).read())
        secondRouter.clear()
        assertFalse(vaults.getValue(AiProvider.OPENROUTER_GEMMA).present())
        assertEquals("fixture-OPENAI", vaults.getValue(AiProvider.OPENAI).read())
        assertEquals("fixture-GROQ_QWEN", vaults.getValue(AiProvider.GROQ_QWEN).read())
    }
    @Test fun `ciphertext cannot move between services even with identical fixture AES bytes`() = runBlocking {
        val vaults = providers.associateWith { AndroidTransformSecrets(app, Keys(), it) }
        for ((provider, vault) in vaults) vault.save("fixture-" + provider.name)
        val stored = providers.associateWith { app.getSharedPreferences(AndroidTransformSecrets.prefsName(it), 0).getString("encrypted", null) }
        for (source in providers) for (target in providers.filter { it != source }) {
            val prefs = app.getSharedPreferences(AndroidTransformSecrets.prefsName(target), 0)
            prefs.edit().putString("encrypted", stored.getValue(source)).commit()
            val failure = runCatching { vaults.getValue(target).read() }.exceptionOrNull() as TransformException
            assertEquals(TransformProblem.KEY_UNAVAILABLE, failure.problem)
            prefs.edit().putString("encrypted", stored.getValue(target)).commit()
        }
    }
    @Test fun `distinct aliases and both Android backup paths exclude every service vault`() {
        val aliases = listOf(AndroidTransformSecrets.ALIAS, AndroidTransformSecrets.GROQ_ALIAS,
            AndroidTransformSecrets.GEMINI_ALIAS, AndroidTransformSecrets.OPENROUTER_ALIAS)
        assertEquals(4, aliases.toSet().size)
        for (alias in aliases) assertEquals(alias, AndroidTransformSecrets.spec(alias).keystoreAlias)
        for (resource in listOf(R.xml.backup_rules, R.xml.data_extraction_rules)) {
            val counts = mutableMapOf<String, Int>()
            app.resources.getXml(resource).use { parser ->
                while (parser.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "exclude" && parser.getAttributeValue(null, "domain") == "sharedpref") {
                        val path = parser.getAttributeValue(null, "path"); counts[path] = (counts[path] ?: 0) + 1
                    }
                    parser.next()
                }
            }
            for (provider in providers) assertEquals(if (resource == R.xml.backup_rules) 1 else 2,
                counts[AndroidTransformSecrets.prefsName(provider) + ".xml"] ?: 0)
        }
    }
}
