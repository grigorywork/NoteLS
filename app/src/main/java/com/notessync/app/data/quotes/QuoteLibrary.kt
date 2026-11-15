package com.notessync.app.data.quotes

import androidx.room.withTransaction
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.CustomQuoteEntity
import com.notessync.app.data.local.entity.QuoteLibraryState

data class QuoteImportPreview(val entries: List<QuoteDraft>, val newCount: Int, val duplicates: Int)
data class QuoteImportResult(val added: Int, val duplicates: Int)

/** All identities, limits and duplicates are checked against fresh rows inside the write transaction. */
class QuoteLibrary(private val database: AppDatabase) {
    private val dao get() = database.customQuoteDao()
    private suspend fun current(expected: CustomQuoteEntity) {
        if (dao.get(expected.id) != expected) throw QuoteException(QuoteProblem.STALE)
    }
    suspend fun save(original: CustomQuoteEntity?, draft: QuoteDraft): Long {
        val value = draft.normalized(); val hash = value.fingerprint()
        return database.withTransaction {
            if (original != null) current(original)
            val other = dao.duplicate(hash)
            if (other != null && other.id != original?.id) throw QuoteException(QuoteProblem.DUPLICATE)
            if (original == null && dao.count() >= QuoteImport.MAX_LIBRARY) throw QuoteException(QuoteProblem.LIMIT)
            val quote = CustomQuoteEntity(original?.id ?: 0, value.text, value.author, value.source, hash)
            if (original == null) dao.insert(quote).also { if (it < 0) throw QuoteException(QuoteProblem.DUPLICATE) }
            else { check(dao.update(quote) == 1); original.id }
        }
    }
    suspend fun delete(quote: CustomQuoteEntity) = database.withTransaction { current(quote); check(dao.delete(quote.id) == 1) }
    suspend fun pin(quote: CustomQuoteEntity) = database.withTransaction { current(quote); dao.pin(QuoteLibraryState(pinnedQuoteId = quote.id)) }
    suspend fun clearPin(expectedId: Long? = null) = database.withTransaction {
        if (expectedId != null && dao.pinned()?.id != expectedId) throw QuoteException(QuoteProblem.STALE)
        dao.pin(QuoteLibraryState())
    }
    suspend fun preview(entries: List<QuoteDraft>): QuoteImportPreview = database.withTransaction {
        val values = validate(entries); val known = dao.fingerprints().toHashSet()
        val count = values.count { known.add(it.fingerprint()) }
        if (dao.count() + count > QuoteImport.MAX_LIBRARY) throw QuoteException(QuoteProblem.LIMIT)
        QuoteImportPreview(values, count, values.size - count)
    }
    suspend fun import(preview: QuoteImportPreview): QuoteImportResult = database.withTransaction {
        val values = validate(preview.entries); var added = 0
        for (value in values) {
            val hash = value.fingerprint()
            if (dao.duplicate(hash) != null) continue
            if (dao.count() >= QuoteImport.MAX_LIBRARY) throw QuoteException(QuoteProblem.LIMIT)
            if (dao.insert(CustomQuoteEntity(text = value.text, author = value.author, source = value.source, fingerprint = hash)) >= 0) added++
        }
        QuoteImportResult(added, values.size - added)
    }
    private fun validate(entries: List<QuoteDraft>): List<QuoteDraft> {
        if (entries.isEmpty()) throw QuoteException(QuoteProblem.INVALID)
        if (entries.size > QuoteImport.MAX_ENTRIES) throw QuoteException(QuoteProblem.TOO_MANY)
        return entries.map { it.normalized() }
    }
}
