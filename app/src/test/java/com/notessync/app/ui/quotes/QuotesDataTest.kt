package com.notessync.app.ui.quotes

import org.junit.Assert.*
import org.junit.Test

class QuotesDataTest {
    @Test fun `daily quote works before Unix epoch and repeats after complete cycle`() {
        assertEquals(QuotesData.quotes.last(), QuotesData.quoteOfDay(-1))
        assertEquals(QuotesData.quoteOfDay(10), QuotesData.quoteOfDay(10L + QuotesData.quotes.size))
    }
    @Test fun `filters include only selected author and all quotes have an existing author`() {
        QuotesData.quotes.forEach { assertEquals(it.authorId, QuotesData.author(it.authorId).id) }
        QuotesData.authors.forEach { author ->
            assertTrue(QuotesData.ofAuthor(author.id).isNotEmpty())
            assertTrue(QuotesData.ofAuthor(author.id).all { it.authorId == author.id })
        }
    }
    @Test fun `copy share and save preserve author and work attribution`() {
        val quote = Quote("seneca", "Пример", "Письма")
        assertEquals("«Пример»\n\n— Сенека, Письма", QuotesData.formatted(quote))
    }
    @Test fun `list identities do not collide`() {
        assertEquals(QuotesData.quotes.size, QuotesData.quotes.map { it.authorId + ":" + it.text }.toSet().size)
    }
}
