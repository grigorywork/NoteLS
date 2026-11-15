package com.notessync.app.ai

import android.app.Application
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
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

/** Offline HTTP contract fixtures; no API account, real TLS connection or paid request. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class OpenAiTransportTest {
    private open class Connection(val status: Int, val body: ByteArray) : HttpsURLConnection(URL("https://api.openai.com/v1/responses")) {
        val sent = ByteArrayOutputStream(); val closed = AtomicBoolean(false)
        override fun connect() = Unit
        override fun disconnect() { closed.set(true) }
        override fun usingProxy() = false
        override fun getOutputStream(): OutputStream = sent
        override fun getInputStream(): InputStream = ByteArrayInputStream(body)
        override fun getResponseCode() = status
        override fun getErrorStream(): InputStream = ByteArrayInputStream(body)
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }
    private fun response(text: String = "Result 😀", status: String = "completed", kind: String = "output_text"): ByteArray = JSONObject()
        .put("status", status).put("error", JSONObject.NULL).put("incomplete_details", JSONObject.NULL)
        .put("output", JSONArray().put(JSONObject().put("type", "reasoning").put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", "Ignored reasoning"))))
            .put(JSONObject().put("type", "message").put("role", "assistant").put("status", "completed").put("content", JSONArray().put(JSONObject().put("type", kind).put("text", text)))))
        .toString().toByteArray()
    @Test fun `only explicit input and settings enter the fixed TLS request and only completed assistant text is returned`() = runBlocking {
        val call = Connection(200, response())
        assertEquals("Result 😀", OpenAiTransport { call }.transform("fixture-key", "fixture-model", "Selected %1" + '$' + "s 😀", TransformMode.LIST))
        val payload = JSONObject(call.sent.toString("UTF-8"))
        assertEquals("Selected %1" + '$' + "s 😀", payload.getString("input")); assertEquals("fixture-model", payload.getString("model"))
        assertFalse(payload.getBoolean("store")); assertFalse(payload.getBoolean("background")); assertFalse(payload.getBoolean("stream"))
        assertEquals(0, payload.getJSONArray("tools").length()); assertEquals("none", payload.getString("tool_choice"))
        assertFalse(payload.has("noteId")); assertFalse(payload.has("attachments")); assertFalse(payload.has("title"))
        assertEquals("Bearer fixture-key", call.getRequestProperty("Authorization")); assertFalse(call.instanceFollowRedirects); assertTrue(call.closed.get())
    }
    @Test fun `HTTP errors and incomplete refusal or oversized replies never produce replacement text`() = runBlocking {
        for ((code, problem) in listOf(401 to TransformProblem.UNAUTHORIZED, 429 to TransformProblem.RATE_LIMIT, 302 to TransformProblem.PROVIDER)) {
            val error = runCatching { OpenAiTransport { Connection(code, response()) }.transform("fixture", "model", "Input", TransformMode.TEXT) }.exceptionOrNull()
            assertEquals(problem, (error as TransformException).problem)
        }
        for ((body, problem) in listOf(response(status = "incomplete") to TransformProblem.INCOMPLETE,
            response(kind = "refusal") to TransformProblem.REFUSED, ByteArray(TransformLimits.RESPONSE_BYTES + 1) { 32 } to TransformProblem.TOO_LARGE)) {
            val error = runCatching { OpenAiTransport { Connection(200, body) }.transform("fixture", "model", "Input", TransformMode.TEXT) }.exceptionOrNull()
            assertEquals(problem, (error as TransformException).problem)
        }
    }
    @Test fun `cancel closes a blocked fixture connection and cannot return a late result`() = runBlocking {
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        val call = object : Connection(200, byteArrayOf()) {
            override fun getInputStream(): InputStream = object : InputStream() {
                override fun read(): Int { started.countDown(); release.await(5, TimeUnit.SECONDS); throw IOException("closed fixture") }
            }
            override fun disconnect() { super.disconnect(); release.countDown() }
        }
        val work = async { OpenAiTransport { call }.transform("fixture", "model", "Input", TransformMode.TEXT) }
        yield(); assertTrue(started.await(5, TimeUnit.SECONDS)); work.cancel()
        withTimeout(5_000) { work.join() }; assertTrue(call.closed.get()); assertTrue(work.isCancelled)
    }
    private fun failure(code: Int, body: ByteArray): TransformException = runBlocking {
        runCatching { OpenAiTransport { Connection(code, body) }.transform("fixture-key", "fixture-model", "Private draft", TransformMode.TEXT) }
            .exceptionOrNull() as TransformException
    }
    private fun providerError(code: String?, type: String? = null) = JSONObject().put("error", JSONObject()
        .put("code", code ?: JSONObject.NULL).put("type", type ?: JSONObject.NULL)
        .put("message", "Do not expose fixture-key or Private draft")).toString().toByteArray()
    @Test fun `billing exhaustion is distinct from rate limiting including a type-only provider response`() {
        assertEquals(TransformProblem.BILLING, failure(429, providerError("insufficient_quota")).problem)
        assertEquals(TransformProblem.BILLING, failure(429, providerError(null, "insufficient_quota")).problem)
        assertEquals(TransformProblem.BILLING, failure(400, providerError("billing_hard_limit_reached")).problem)
        assertEquals(TransformProblem.RATE_LIMIT, failure(429, providerError("rate_limit_exceeded")).problem)
        assertEquals(TransformProblem.BILLING, failure(402, byteArrayOf()).problem)
    }
    @Test fun `model permissions region and service errors use safe actionable classifications`() {
        val cases = listOf(
            Triple(401, providerError("invalid_api_key"), TransformProblem.UNAUTHORIZED),
            Triple(403, providerError("insufficient_permissions"), TransformProblem.FORBIDDEN),
            Triple(403, providerError("unsupported_country_region_territory"), TransformProblem.REGION),
            Triple(404, providerError("model_not_found"), TransformProblem.MODEL_UNAVAILABLE),
            Triple(400, providerError("unsupported_model"), TransformProblem.MODEL_UNAVAILABLE),
            Triple(400, providerError(null, "invalid_request_error"), TransformProblem.BAD_REQUEST),
            Triple(503, providerError("server_error"), TransformProblem.SERVICE_UNAVAILABLE),
            Triple(504, byteArrayOf(), TransformProblem.TIMEOUT))
        for ((status, body, problem) in cases) {
            val error = failure(status, body)
            assertEquals(problem, error.problem)
            assertFalse(error.toString().contains("fixture-key")); assertFalse(error.toString().contains("Private draft"))
        }
    }
    @Test fun `malformed and oversized HTTP error bodies retain status fallback and close within a strict bound`() = runBlocking {
        for (body in listOf("<html>Private draft</html>".toByteArray(), byteArrayOf(0xc3.toByte(), 0x28))) {
            assertEquals(TransformProblem.RATE_LIMIT, failure(429, body).problem)
        }
        var read = 0; var released = false
        val call = object : Connection(429, byteArrayOf()) {
            override fun getErrorStream(): InputStream = object : InputStream() {
                override fun read(): Int { read++; return 32 }
                override fun close() { released = true }
            }
        }
        val error = runCatching { OpenAiTransport { call }.transform("fixture", "model", "Draft", TransformMode.TEXT) }.exceptionOrNull() as TransformException
        assertEquals(TransformProblem.RATE_LIMIT, error.problem)
        assertTrue(read <= 8193); assertTrue(released); assertTrue(call.closed.get())
    }
    @Test fun `reasoning output exhaustion never returns partial text or silently increases the token budget`() {
        val body = JSONObject(String(response())).put("status", "incomplete")
            .put("incomplete_details", JSONObject().put("reason", "max_output_tokens")).toString().toByteArray()
        val call = Connection(200, body)
        val error = runBlocking { runCatching { OpenAiTransport { call }.transform("fixture", "model", "Draft", TransformMode.TEXT) }.exceptionOrNull() as TransformException }
        assertEquals(TransformProblem.OUTPUT_LIMIT, error.problem)
        assertEquals(2048, JSONObject(call.sent.toString("UTF-8")).getInt("max_output_tokens")); assertTrue(call.closed.get())
    }
    @Test fun `connection setup failures map to network without echoing exception details`() = runBlocking {
        val error = runCatching { OpenAiTransport { throw IOException("fixture-key Private draft") }.transform("fixture", "model", "Draft", TransformMode.TEXT) }.exceptionOrNull() as TransformException
        assertEquals(TransformProblem.NETWORK, error.problem)
        assertFalse(error.toString().contains("Private draft")); assertFalse(error.toString().contains("fixture-key"))
    }
}
