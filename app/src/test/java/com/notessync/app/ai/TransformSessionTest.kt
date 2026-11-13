package com.notessync.app.ai

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TransformSessionTest {
    private class Secrets : TransformSecrets {
        var value: String? = "fixture-key"; var reads = 0
        override suspend fun read(): String? { reads++; return value }
        override suspend fun present() = value != null
        override suspend fun save(value: String) { this.value = value }
        override suspend fun clear() { value = null }
    }
    @Test fun `default off and missing configuration never invoke transport or apply`() = runTest {
        var config = TransformConfig(); val secrets = Secrets(); var calls = 0; var applied = 0
        fun session() = TransformSession({ config }, secrets, object : TransformTransport {
            override suspend fun transform(key: String, model: String, input: String, mode: TransformMode): String { calls++; return "Result" }
        }, { true }, { applied++; true }, StandardTestDispatcher(testScheduler))
        val off = session(); assertEquals(0, calls); off.generate("Draft", TransformMode.TEXT); advanceUntilIdle()
        assertEquals(TransformProblem.DISABLED, off.state.value.problem); assertEquals(0, secrets.reads); off.close()
        config = TransformConfig(true, AiProvider.OPENAI)
        val missing = session(); missing.generate("Draft", TransformMode.TEXT); advanceUntilIdle()
        assertEquals(TransformProblem.MODEL_MISSING, missing.state.value.problem); assertEquals(0, calls); assertEquals(0, applied); missing.close()
    }
    @Test fun `network result is only a preview and user edited result is applied exactly once`() = runTest {
        val config = TransformConfig(true, AiProvider.OPENAI, "fixture-model")
        val secrets = Secrets(); val received = mutableListOf<String>(); val applied = mutableListOf<String>()
        val session = TransformSession({ config }, secrets, object : TransformTransport {
            override suspend fun transform(key: String, model: String, input: String, mode: TransformMode): String {
                assertEquals("fixture-key", key); assertEquals("fixture-model", model); received.add(input); return "Generated"
            }
        }, { true }, { applied.add(it); true }, StandardTestDispatcher(testScheduler))
        assertTrue(received.isEmpty()); session.generate("Only selected text", TransformMode.LIST); advanceUntilIdle()
        assertEquals(listOf("Only selected text"), received); assertEquals(TransformPhase.RESULT, session.state.value.phase); assertTrue(applied.isEmpty())
        session.replace("Edited preview"); advanceUntilIdle(); session.replace("Another")
        assertEquals(listOf("Edited preview"), applied); assertEquals(TransformPhase.CLOSED, session.state.value.phase)
    }
    @Test fun `source change and disabling prevent late response application and closing cancels in flight work`() = runTest {
        var current = true; var config = TransformConfig(true, AiProvider.OPENAI, "fixture-model"); var applied = false
        val reply = CompletableDeferred<String>(); var canceled = false
        val session = TransformSession({ config }, Secrets(), object : TransformTransport {
            override suspend fun transform(key: String, model: String, input: String, mode: TransformMode): String = try { reply.await() } finally { canceled = true }
        }, { current }, { applied = true; true }, StandardTestDispatcher(testScheduler))
        session.generate("Draft", TransformMode.TEXT); runCurrent(); current = false; reply.complete("Late"); advanceUntilIdle()
        assertEquals(TransformProblem.STALE, session.state.value.problem); assertFalse(applied); session.close(); assertTrue(canceled)
        val pending = CompletableDeferred<String>()
        val second = TransformSession({ config }, Secrets(), object : TransformTransport {
            override suspend fun transform(key: String, model: String, input: String, mode: TransformMode): String = pending.await()
        }, { true }, { applied = true; true }, StandardTestDispatcher(testScheduler))
        second.generate("Draft", TransformMode.TEXT); runCurrent(); config = config.copy(enabled = false); second.close(); pending.complete("Ignored"); advanceUntilIdle()
        assertEquals(TransformPhase.CLOSED, second.state.value.phase); assertFalse(applied)
    }
    @Test fun `offline rules preserve factual words and language and do not touch credentials or transport`() = runTest {
        assertEquals("- первое место занял Иван.\n- Next item!", TransformLimits.local("первое место занял Иван.\nNext item!", TransformMode.LIST))
        assertEquals("English text.", TransformLimits.local(" English   text. ", TransformMode.TEXT))
        val secrets = Secrets(); var called = false
        val session = TransformSession({ TransformConfig(true) }, secrets, object : TransformTransport {
            override suspend fun transform(key: String, model: String, input: String, mode: TransformMode): String { called = true; return "" }
        }, { true }, { true }, StandardTestDispatcher(testScheduler))
        session.generate("One. Two.", TransformMode.LIST)
        while (session.state.value.phase == TransformPhase.WORKING) { runCurrent(); delay(1) }
        assertEquals(TransformPhase.RESULT, session.state.value.phase); assertEquals(0, secrets.reads); assertFalse(called); session.close()
        assertTrue(runCatching { TransformLimits.input("x".repeat(TransformLimits.INPUT + 1)) }.isFailure)
        assertFalse(TransformLimits.validKey("bad\r\nkey")); assertFalse(TransformLimits.validModel("model\nheader"))
    }
}
