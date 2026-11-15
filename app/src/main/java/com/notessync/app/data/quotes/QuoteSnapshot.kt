package com.notessync.app.data.quotes

import androidx.sqlite.db.SupportSQLiteDatabase

/** Validate restored rows before touching the live library or notes. */
internal fun validateQuoteSnapshot(database: SupportSQLiteDatabase, checkCancelled: () -> Unit) {
    database.query("SELECT COUNT(*) FROM custom_quotes").use { cursor ->
        check(cursor.moveToFirst())
        if (cursor.getLong(0) > QuoteImport.MAX_LIBRARY) throw QuoteException(QuoteProblem.LIMIT)
    }
    database.query("SELECT COUNT(*) FROM quote_library_state WHERE id != 1").use { cursor ->
        check(cursor.moveToFirst()); if (cursor.getLong(0) != 0L) throw QuoteException(QuoteProblem.INVALID)
    }
    database.query("SELECT text,author,source,fingerprint FROM custom_quotes").use { cursor ->
        while (cursor.moveToNext()) {
            checkCancelled()
            val draft = QuoteDraft(cursor.getString(0), cursor.getString(1), cursor.getString(2))
            if (draft.normalized() != draft || draft.fingerprint() != cursor.getString(3)) throw QuoteException(QuoteProblem.INVALID)
        }
    }
}
