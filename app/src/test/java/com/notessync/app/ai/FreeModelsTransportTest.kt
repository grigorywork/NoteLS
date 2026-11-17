package com.notessync.app.ai

import android.app.Application
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.*
import java.net.URL
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

/** Offline HTTP fixtures; no account, user credential or real inference request. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class FreeModelsTransportTest {
    private val providers = listOf(AiProvider.GEMINI_FREE, AiProvider.OPENROUTER_GEMMA, AiProvider.OPENROUTER_NEMOTRON)
    private open class Call(endpoint: String, private val status: Int, private val body: ByteArray) : HttpsURLConnection(URL(endpoint)) {
        val sent = ByteArrayOutputStream()
        val closed = AtomicBoolean(false)
        override fun connect() = Unit
        override fun disconnect() { closed.set(true) }
        override fun usingProxy() = false
        override fun getOutputStream(): OutputStream = sent
        override fun getInputStream(): InputStream = ByteArrayInputStream(body)
        override fun getErrorStream(): InputStream = ByteArrayInputStream(body)
        override fun getResponseCode() = status
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }
    private fun response(text: String = "Результат 😀", reason: String = "stop", role: String = "assistant"): ByteArray =
        JSONObject().put("choices", JSONArray().put(JSONObject().put("index", 0).put("finish_reason", reason)
            .put("message", JSONObject().put("role", role).put("content", text).put("reasoning", "Never apply reasoning"))))
            .toString().toByteArray(Charsets.UTF_8)
    private suspend fun failure(provider: AiProvider, call: Call): TransformException =
        runCatching { FreeModelsTransport(provider) { call }.transform("fixture-key", requireNotNull(provider.fixedModel), "Private draft", TransformMode.TEXT) }
            .exceptionOrNull() as TransformException

    @Test fun `each selected model uses its fixed origin text only and bounded zero price request`() = runBlocking {
        for (provider in providers) {
            val spec = freeModelSpec(provider)
            val call = Call(spec.endpoint, 200, response()); var calls = 0
            val input = "Selected draft 😀"
            assertEquals("Результат 😀", FreeModelsTransport(provider) { calls++; call }
                .transform("fixture-key", spec.model, input, TransformMode.LIST))
            val payload = JSONObject(call.sent.toString("UTF-8"))
            assertEquals(spec.model, payload.getString("model")); assertEquals(2048, payload.getInt("max_tokens"))
            assertFalse(payload.getBoolean("stream")); assertEquals(1, payload.getInt("n"))
            val messages = payload.getJSONArray("messages"); assertEquals(2, messages.length())
            assertEquals("system", messages.getJSONObject(0).getString("role"))
            assertEquals("user", messages.getJSONObject(1).getString("role")); assertEquals(input, messages.getJSONObject(1).getString("content"))
            assertFalse(payload.toString().contains("fixture-key"))
            for (field in listOf("models", "tools", "attachments", "title", "noteId", "service_tier")) assertFalse(field, payload.has(field))
            if (provider.isOpenRouter) {
                assertTrue(spec.model.endsWith(":free"))
                val route = payload.getJSONObject("provider")
                assertFalse(route.getBoolean("allow_fallbacks"))
                assertEquals(0, route.getJSONObject("max_price").getInt("prompt"))
                assertEquals(0, route.getJSONObject("max_price").getInt("completion"))
            } else assertFalse(payload.has("provider"))
            assertEquals("Bearer fixture-key", call.getRequestProperty("Authorization"))
            assertEquals(10_000, call.connectTimeout); assertEquals(30_000, call.readTimeout)
            assertFalse(call.instanceFollowRedirects); assertFalse(call.useCaches); assertTrue(call.closed.get()); assertEquals(1, calls)
        }
    }
    @Test fun `paid or other model is rejected before connection and wrong origin never receives credentials`() = runBlocking {
        for (provider in providers) {
            var calls = 0
            val denied = runCatching { FreeModelsTransport(provider) { calls++; error("Unexpected connection") }
                .transform("fixture-key", "paid-model", "Draft", TransformMode.TEXT) }.exceptionOrNull() as TransformException
            assertEquals(TransformProblem.MODEL_UNAVAILABLE, denied.problem); assertEquals(0, calls)
            val call = Call("https://example.invalid/collect", 200, response())
            assertEquals(TransformProblem.PROVIDER, failure(provider, call).problem)
            assertNull(call.getRequestProperty("Authorization")); assertEquals(0, call.sent.size()); assertTrue(call.closed.get())
        }
    }
    @Test fun `quota model denial and server failures stop after one call without sensitive error text`() = runBlocking {
        for (provider in providers) for ((status, expected) in listOf(401 to TransformProblem.UNAUTHORIZED,
            402 to TransformProblem.BILLING, 404 to TransformProblem.MODEL_UNAVAILABLE, 429 to TransformProblem.RATE_LIMIT,
            503 to TransformProblem.SERVICE_UNAVAILABLE, 302 to TransformProblem.PROVIDER)) {
            val call = Call(freeModelSpec(provider).endpoint, status,
                JSONObject().put("error", JSONObject().put("message", "fixture-key Private draft")).toString().toByteArray())
            var calls = 0
            val error = runCatching { FreeModelsTransport(provider) { calls++; call }
                .transform("fixture-key", requireNotNull(provider.fixedModel), "Private draft", TransformMode.TEXT) }
                .exceptionOrNull() as TransformException
            assertEquals(expected, error.problem); assertEquals(1, calls); assertTrue(call.closed.get())
            assertFalse(error.toString().contains("fixture-key")); assertFalse(error.toString().contains("Private draft"))
        }
    }
    @Test fun `oversized invalid UTF8 partial wrong role and tool responses never reach replacement`() = runBlocking {
        for (provider in providers) {
            val cases = listOf(ByteArray(TransformLimits.RESPONSE_BYTES + 1) { 32 } to TransformProblem.TOO_LARGE,
                byteArrayOf(0xc3.toByte(), 0x28) to TransformProblem.PROVIDER,
                response("x".repeat(TransformLimits.OUTPUT + 1)) to TransformProblem.TOO_LARGE,
                response(reason = "length") to TransformProblem.OUTPUT_LIMIT,
                response(reason = "tool_calls") to TransformProblem.INCOMPLETE,
                response(role = "user") to TransformProblem.INCOMPLETE,
                response(" ") to TransformProblem.PROVIDER)
            for ((body, expected) in cases) assertEquals(expected, failure(provider, Call(freeModelSpec(provider).endpoint, 200, body)).problem)
            val tools = JSONObject(String(response(), Charsets.UTF_8))
            tools.getJSONArray("choices").getJSONObject(0).getJSONObject("message").put("tool_calls", JSONArray().put(JSONObject()))
            assertEquals(TransformProblem.INCOMPLETE, failure(provider, Call(freeModelSpec(provider).endpoint, 200, tools.toString().toByteArray())).problem)
        }
    }
    @Test fun `error bodies are bounded and setup exceptions do not echo their details`() = runBlocking {
        for (provider in providers) {
            var read = 0; var closed = false
            val call = object : Call(freeModelSpec(provider).endpoint, 429, byteArrayOf()) {
                override fun getErrorStream(): InputStream = object : InputStream() {
                    override fun read(): Int { read++; return 32 }
                    override fun close() { closed = true }
                }
            }
            assertEquals(TransformProblem.RATE_LIMIT, failure(provider, call).problem)
            assertTrue(read <= 8193); assertTrue(closed); assertTrue(call.closed.get())
            val error = runCatching { FreeModelsTransport(provider) { throw IOException("fixture-key Private draft") }
                .transform("fixture-key", requireNotNull(provider.fixedModel), "Private draft", TransformMode.TEXT) }
                .exceptionOrNull() as TransformException
            assertEquals(TransformProblem.NETWORK, error.problem); assertFalse(error.toString().contains("fixture-key"))
        }
    }
    @Test fun `cancellation disconnects blocked IO and produces no late result`() = runBlocking {
        val provider = AiProvider.OPENROUTER_GEMMA
        val started = CountDownLatch(1); val released = CountDownLatch(1)
        val call = object : Call(freeModelSpec(provider).endpoint, 200, byteArrayOf()) {
            override fun getInputStream(): InputStream = object : InputStream() {
                override fun read(): Int { started.countDown(); released.await(5, TimeUnit.SECONDS); throw IOException("closed fixture") }
            }
            override fun disconnect() { super.disconnect(); released.countDown() }
        }
        var result: String? = null
        val job = launch(Dispatchers.IO) { result = FreeModelsTransport(provider) { call }
            .transform("fixture-key", requireNotNull(provider.fixedModel), "Draft", TransformMode.TEXT) }
        assertTrue(started.await(5, TimeUnit.SECONDS)); job.cancelAndJoin()
        assertTrue(call.closed.get()); assertNull(result)
    }
}
