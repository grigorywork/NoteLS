package com.notessync.app.data.quotes

import kotlinx.coroutines.*
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class QuoteImportTest {
    private fun parse(text: String) = QuoteImport.read(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
    private fun problem(block: () -> Any?): QuoteProblem = (runCatching(block).exceptionOrNull() as QuoteException).problem
    @Test fun `JSON and TXT preserve Unicode line breaks attribution and BOM with optional author`() {
        val json = parse("\uFEFF [{\"text\":\"  Пример 😀\\nВторая строка  \",\"author\":\" Автор \",\"source\":\"Книга\"}]")
        assertEquals(QuoteDraft("Пример 😀\nВторая строка", "Автор", "Книга"), json.single())
        assertEquals("«Пример 😀\nВторая строка»\n\n— Автор, Книга", json.single().formatted())
        assertEquals(listOf(QuoteDraft("First", "Author", "Source"), QuoteDraft("Без автора")), parse("\uFEFFFirst\tAuthor\tSource\r\n\r\nБез автора\n"))
    }
    @Test fun `duplicate identity normalizes case whitespace Unicode composition and ignores source without merging authors`() {
        val first = QuoteDraft("  Cafe\u0301  и\nИДЕЯ", "Иван", "one").normalized()
        val same = QuoteDraft("Café и идея", "иван", "two").normalized()
        assertEquals(first.fingerprint(), same.fingerprint())
        assertNotEquals(first.fingerprint(), same.copy(author = "Другой").fingerprint())
        assertNotEquals(QuoteDraft("a", "bc").fingerprint(), QuoteDraft("ab", "c").fingerprint())
    }
    @Test fun `malformed JSON wrong types extra fields empty text and malformed Unicode reject whole input`() {
        for (text in listOf("[]", "{}", "[{", "[1]", "[{\"text\":5}]", "[{\"text\":\"ok\",\"other\":1}]",
            "[{\"text\":\"good\"},{\"author\":\"missing\"}]", "[{\"text\":\" \"}]", "[{\"text\":\"\\u0000\"}]", "[{\"text\":\"\\ud800\"}]", "\tOnly an author")) {
            assertEquals(text, QuoteProblem.INVALID, problem { parse(text) })
        }
        assertEquals(QuoteProblem.INVALID, problem { QuoteImport.read(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28))) })
    }
    @Test fun `excess nesting and fields are bounded while brackets inside strings are ordinary text`() {
        assertEquals("[[[ text ]]]", parse("[{\"text\":\"[[[ text ]]]\"}]").single().text)
        assertEquals(QuoteProblem.INVALID, problem { parse("[".repeat(9) + "0" + "]".repeat(9)) })
        assertEquals(QuoteProblem.INVALID, problem { parse("x".repeat(3001)) })
        assertEquals(QuoteProblem.INVALID, problem { parse("text\t" + "a".repeat(161)) })
        assertEquals(QuoteProblem.INVALID, problem { parse("text\ta\tb\textra") })
    }
    @Test fun `too many entries do not silently truncate JSON or TXT`() {
        assertEquals(QuoteProblem.TOO_MANY, problem { parse((1..501).joinToString("\n") { "Quote $it" }) })
        assertEquals(QuoteProblem.TOO_MANY, problem { parse((1..501).joinToString(",", "[", "]") { "{\"text\":\"$it\"}" }) })
        assertEquals(500, parse((1..500).joinToString("\n") { "Quote $it" }).size)
    }
    @Test fun `oversized streams stop after the bounded extra byte`() {
        var bytes = 0
        val input = object : InputStream() {
            override fun read(): Int { bytes++; return 65 }
        }
        assertEquals(QuoteProblem.FILE_TOO_LARGE, problem { QuoteImport.read(input) })
        assertEquals(QuoteImport.MAX_BYTES + 1, bytes)
    }
    @Test fun `cancelling an import closes a blocked stream and cannot return late content`() = runBlocking {
        val started = CountDownLatch(1); val released = CountDownLatch(1); val closed = AtomicBoolean(false)
        val input = object : InputStream() {
            override fun read(): Int { started.countDown(); released.await(5, TimeUnit.SECONDS); throw IOException("fixture") }
            override fun close() { closed.set(true); released.countDown() }
        }
        val result = async { QuoteImport.readCancellable { input } }
        yield(); assertTrue(started.await(5, TimeUnit.SECONDS)); result.cancel()
        withTimeout(5000) { result.join() }; assertTrue(result.isCancelled); assertTrue(closed.get())
    }
}
