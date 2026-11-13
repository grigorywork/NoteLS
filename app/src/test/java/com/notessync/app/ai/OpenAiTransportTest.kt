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
}
