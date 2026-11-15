package com.notessync.app.ai

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GroqSessionTest {
    private class Secrets(val value: suspend () -> String) : TransformSecrets {
        var reads = 0
        override suspend fun read(): String { reads++; return value() }
        override suspend fun present() = true
        override suspend fun save(value: String) = Unit
        override suspend fun clear() = Unit
    }
    @Test fun `provider change between service binding and generation or during credential read cannot send to either origin`() = runTest {
        val bound = TransformConfig(true, AiProvider.GROQ_QWEN); var config = bound.copy(provider = AiProvider.OPENAI, model = "fixture-openai")
        var calls = 0
        val transport = object : TransformTransport {
            override suspend fun transform(key: String, model: String, input: String, mode: TransformMode): String { calls++; return "Candidate" }
        }
        val unused = Secrets { "groq-fixture" }
        val early = TransformSession({ config }, unused, transport, { true }, { true }, StandardTestDispatcher(testScheduler), presented = bound)
        early.generate("Draft", TransformMode.TEXT); advanceUntilIdle()
        assertEquals(TransformProblem.STALE, early.state.value.problem); assertEquals(0, unused.reads); assertEquals(0, calls); early.close()
        config = bound
        val switching = Secrets { config = config.copy(provider = AiProvider.OPENAI, model = "fixture-openai"); "groq-fixture" }
        val late = TransformSession({ config }, switching, transport, { true }, { true }, StandardTestDispatcher(testScheduler), presented = bound)
        late.generate("Draft", TransformMode.TEXT); advanceUntilIdle()
        assertEquals(TransformProblem.STALE, late.state.value.problem); assertEquals(1, switching.reads); assertEquals(0, calls); late.close()
    }
    @Test fun `provider change during Qwen response rejects a late result without rerouting or applying`() = runTest {
        var config = TransformConfig(true, AiProvider.GROQ_QWEN); val response = CompletableDeferred<String>(); var calls = 0; var applies = 0
        val session = TransformSession({ config }, Secrets { "groq-fixture" }, object : TransformTransport {
            override suspend fun transform(key: String, model: String, input: String, mode: TransformMode): String {
                calls++; assertEquals("groq-fixture", key); assertEquals(GroqQwenTransport.MODEL, model); return response.await()
            }
        }, { true }, { applies++; true }, StandardTestDispatcher(testScheduler))
        session.generate("Draft", TransformMode.TEXT); runCurrent()
        config = config.copy(provider = AiProvider.OPENAI, model = "fixture-openai"); response.complete("Late Qwen"); advanceUntilIdle()
        assertEquals(TransformProblem.STALE, session.state.value.problem); assertEquals("", session.state.value.result)
        session.replace("Late Qwen"); advanceUntilIdle(); assertEquals(1, calls); assertEquals(0, applies); session.close()
    }
}
