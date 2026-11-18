package com.notessync.app.notes.diary

import androidx.sqlite.db.SupportSQLiteDatabase

/** Validate the migrated staging copy before a restore can replace any live table. */
internal fun validateDiarySnapshot(db: SupportSQLiteDatabase, checkCancelled: () -> Unit) {
    db.query("SELECT isDeleted, isProtected, passwordHash, passwordSalt FROM notebooks WHERE isDiary=1").use { rows ->
        while (rows.moveToNext()) {
            checkCancelled()
            check(rows.getInt(0) == 0 && rows.getInt(1) == 1 &&
                DiaryPrivacy.validCredentials(rows.getString(2), rows.getString(3))) { "Invalid private diary metadata" }
        }
    }
}
