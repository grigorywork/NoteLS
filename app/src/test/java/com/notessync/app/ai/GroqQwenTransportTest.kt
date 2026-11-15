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

/** Offline fixtures only. No Groq account, credentials or inference request. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class GroqQwenTransportTest {
    private open class Connection(val status: Int, val body: ByteArray, endpoint: String = GroqQwenTransport.ENDPOINT) : HttpsURLConnection(URL(endpoint)) {
        val sent = ByteArrayOutputStream(); val closed = AtomicBoolean(false)
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
    private fun response(text: String = "Результат 😀", reason: String = "stop", role: String = "assistant"): ByteArray = JSONObject()
        .put("choices", JSONArray().put(JSONObject().put("index", 0).put("finish_reason", reason)
            .put("message", JSONObject().put("role", role).put("content", text).put("reasoning", "Never return reasoning"))))
        .toString().toByteArray()
    private fun error(call: Connection) = runBlocking {
        runCatching { GroqQwenTransport { call }.transform("groq-fixture-key", GroqQwenTransport.MODEL, "Private draft", TransformMode.TEXT) }.exceptionOrNull() as TransformException
    }
    @Test fun `fixed Qwen request contains only the selected text with bounded nonthinking output and no paid fallback`() = runBlocking {
        val call = Connection(200, response()); var calls = 0
        val input = "Выделенный %1" + '$' + "s 😀"
        assertEquals("Результат 😀", GroqQwenTransport { calls++; call }.transform("groq-fixture-key", GroqQwenTransport.MODEL, input, TransformMode.LIST))
        val payload = JSONObject(call.sent.toString("UTF-8"))
        assertEquals(GroqQwenTransport.MODEL, payload.getString("model")); assertEquals(2048, payload.getInt("max_completion_tokens"))
        assertEquals("none", payload.getString("reasoning_effort")); assertEquals("none", payload.getString("tool_choice"))
        assertEquals(1, payload.getInt("n")); assertFalse(payload.getBoolean("stream"))
        val messages = payload.getJSONArray("messages"); assertEquals(2, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role")); assertTrue(messages.getJSONObject(0).getString("content").contains("bullet list"))
        assertEquals("user", messages.getJSONObject(1).getString("role")); assertEquals(input, messages.getJSONObject(1).getString("content"))
        assertFalse(payload.toString().contains("groq-fixture-key"))
        for (field in listOf("tools", "attachments", "title", "noteId", "store", "models", "provider", "service_tier")) assertFalse(field, payload.has(field))
        assertEquals("Bearer groq-fixture-key", call.getRequestProperty("Authorization"))
        assertEquals(10_000, call.connectTimeout); assertEquals(30_000, call.readTimeout)
        assertFalse(call.instanceFollowRedirects); assertFalse(call.useCaches); assertTrue(call.closed.get()); assertEquals(1, calls)
    }
    @Test fun `other model or origin cannot receive the credential`() = runBlocking {
        var calls = 0
        val transport = GroqQwenTransport { calls++; Connection(200, response()) }
        val wrong = runCatching { transport.transform("fixture", "paid-model", "Draft", TransformMode.TEXT) }.exceptionOrNull() as TransformException
        assertEquals(TransformProblem.MODEL_UNAVAILABLE, wrong.problem); assertEquals(0, calls)
        val call = Connection(200, response(), "https://api.openai.com/v1/responses")
        assertEquals(TransformProblem.PROVIDER, error(call).problem)
        assertNull(call.getRequestProperty("Authorization")); assertEquals(0, call.sent.size()); assertTrue(call.closed.get())
    }
    @Test fun `rate limit never retries or changes service and error reading stops within its bound`() = runBlocking {
        var calls = 0; var read = 0; var streamClosed = false
        val call = object : Connection(429, byteArrayOf()) {
            override fun getErrorStream(): InputStream = object : InputStream() {
                override fun read(): Int { read++; return 32 }
                override fun close() { streamClosed = true }
            }
        }
        val failure = runCatching { GroqQwenTransport { calls++; call }.transform("fixture", GroqQwenTransport.MODEL, "Draft", TransformMode.TEXT) }.exceptionOrNull() as TransformException
        assertEquals(TransformProblem.RATE_LIMIT, failure.problem); assertEquals(1, calls)
        assertTrue(read <= 8193); assertTrue(streamClosed); assertTrue(call.closed.get())
    }
    @Test fun `status and whitelisted codes explain failures without echoing provider error text`() {
        for ((status, code, expected) in listOf(
            Triple(401, "invalid_api_key", TransformProblem.UNAUTHORIZED), Triple(402, "", TransformProblem.BILLING),
            Triple(403, "permission_denied", TransformProblem.FORBIDDEN), Triple(403, "unsupported_country", TransformProblem.REGION),
            Triple(404, "model_not_found", TransformProblem.MODEL_UNAVAILABLE), Triple(400, "model_decommissioned", TransformProblem.MODEL_UNAVAILABLE),
            Triple(400, "bad_request", TransformProblem.BAD_REQUEST), Triple(413, "", TransformProblem.TOO_LARGE),
            Triple(429, "rate_limit_exceeded", TransformProblem.RATE_LIMIT), Triple(429, "insufficient_quota", TransformProblem.BILLING),
            Triple(503, "", TransformProblem.SERVICE_UNAVAILABLE), Triple(504, "", TransformProblem.TIMEOUT), Triple(302, "", TransformProblem.PROVIDER))) {
            val body = JSONObject().put("error", JSONObject().put("code", code).put("message", "groq-fixture-key Private draft")).toString().toByteArray()
            val call = Connection(status, body); val failure = error(call)
            assertEquals(status.toString(), expected, failure.problem)
            assertFalse(failure.toString().contains("groq-fixture-key")); assertFalse(failure.toString().contains("Private draft")); assertTrue(call.closed.get())
        }
        assertEquals(TransformProblem.RATE_LIMIT, error(Connection(429, "<html>Private draft</html>".toByteArray())).problem)
    }
    @Test fun `incomplete filtered wrong-role and tool responses never become a replacement`() {
        for ((body, expected) in listOf(response(reason = "length") to TransformProblem.OUTPUT_LIMIT,
            response(reason = "content_filter") to TransformProblem.REFUSED, response(reason = "tool_calls") to TransformProblem.INCOMPLETE,
            response(role = "user") to TransformProblem.INCOMPLETE, response(reason = "") to TransformProblem.INCOMPLETE)) {
            assertEquals(expected, error(Connection(200, body)).problem)
        }
        for (field in listOf("refusal", "function_call", "tool_calls")) {
            val body = JSONObject(String(response())); val message = body.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            message.put(field, if (field == "tool_calls") JSONArray().put(JSONObject()) else "unexpected")
            assertEquals(if (field == "refusal") TransformProblem.REFUSED else TransformProblem.INCOMPLETE, error(Connection(200, body.toString().toByteArray())).problem)
        }
    }
    @Test fun `response byte text and JSON boundaries reject malformed or oversized content`() {
        assertEquals(TransformProblem.TOO_LARGE, error(Connection(200, ByteArray(TransformLimits.RESPONSE_BYTES + 1) { 32 })).problem)
        assertEquals(TransformProblem.TOO_LARGE, error(Connection(200, response("x".repeat(TransformLimits.OUTPUT + 1)))).problem)
        for (body in listOf(byteArrayOf(0xc3.toByte(), 0x28), "{}".toByteArray(), response(" "))) {
            assertEquals(TransformProblem.PROVIDER, error(Connection(200, body)).problem)
        }
        val multiple = JSONObject(String(response())); multiple.getJSONArray("choices").put(multiple.getJSONArray("choices").getJSONObject(0))
        assertEquals(TransformProblem.INCOMPLETE, error(Connection(200, multiple.toString().toByteArray())).problem)
    }
    @Test fun `setup failure does not expose sensitive exception details`() = runBlocking {
        val failure = runCatching { GroqQwenTransport { throw IOException("groq-fixture-key Private draft") }.transform("fixture", GroqQwenTransport.MODEL, "Draft", TransformMode.TEXT) }.exceptionOrNull() as TransformException
        assertEquals(TransformProblem.NETWORK, failure.problem)
        assertFalse(failure.toString().contains("groq-fixture-key")); assertFalse(failure.toString().contains("Private draft"))
    }
    @Test fun `cancellation disconnects a blocked request and prevents late results`() = runBlocking {
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        val call = object : Connection(200, byteArrayOf()) {
            override fun getInputStream(): InputStream = object : InputStream() {
                override fun read(): Int { started.countDown(); release.await(5, TimeUnit.SECONDS); throw IOException("closed fixture") }
            }
            override fun disconnect() { super.disconnect(); release.countDown() }
        }
        val work = async { GroqQwenTransport { call }.transform("fixture", GroqQwenTransport.MODEL, "Draft", TransformMode.TEXT) }
        yield(); assertTrue(started.await(5, TimeUnit.SECONDS)); work.cancel()
        withTimeout(5_000) { work.join() }; assertTrue(work.isCancelled); assertTrue(call.closed.get())
    }
}
