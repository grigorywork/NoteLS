package com.notessync.app.data.local

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.UUID

/** Data-only repair also runs on staged legacy restores, before any UI can grant access to an empty ID. */
internal object LegacyIdentityRepair : RoomDatabase.Callback() {
    override fun onOpen(db: SupportSQLiteDatabase) {
        super.onOpen(db)
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            for (table in listOf("notes", "notebooks")) {
                db.compileStatement("UPDATE `$table` SET syncId = ?, dirty = 1, updatedAt = " +
                    "CASE WHEN updatedAt < 9223372036854775807 THEN MAX(updatedAt + 1, ?) ELSE updatedAt END " +
                    "WHERE id = ? AND syncId = ''").use { update ->
                    while (true) {
                        val ids = db.query("SELECT id FROM `$table` WHERE syncId = '' LIMIT 128").use { cursor ->
                            buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
                        }
                        if (ids.isEmpty()) break
                        for (id in ids) {
                            update.bindString(1, UUID.randomUUID().toString()); update.bindLong(2, now)
                            update.bindLong(3, id); update.executeUpdateDelete()
                        }
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}
