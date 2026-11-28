package com.notessync.app.data.local

import android.content.Context
import com.notessync.app.data.local.entity.CustomQuoteEntity
import com.notessync.app.data.local.entity.QuoteLibraryState
import com.notessync.app.data.local.dao.CustomQuoteDao
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.InvalidationTracker
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.notessync.app.data.local.dao.AttachmentDao
import com.notessync.app.data.local.dao.NoteDao
import com.notessync.app.data.local.dao.NotebookDao
import com.notessync.app.data.local.dao.TagDao
import com.notessync.app.data.local.entity.AttachmentEntity
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.data.local.entity.NoteTagCrossRef
import com.notessync.app.data.local.entity.NotebookEntity
import com.notessync.app.data.local.entity.TagEntity
import com.notessync.app.data.backup.BackupManager

@Database(
    entities = [
        NoteEntity::class,
        NotebookEntity::class,
        TagEntity::class,
        NoteTagCrossRef::class,
        AttachmentEntity::class,
        CustomQuoteEntity::class,
        QuoteLibraryState::class
    ],
    version = 19,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun customQuoteDao(): CustomQuoteDao
    abstract fun noteDao(): NoteDao
    abstract fun notebookDao(): NotebookDao
    abstract fun tagDao(): TagDao
    abstract fun attachmentDao(): AttachmentDao

    companion object {
        const val VERSION = 19
        @Volatile
        private var instance: AppDatabase? = null

        // v1 -> v2 (Этап 5): поля синхронизации.
        // syncId по умолчанию пуст — UUID присваивается при первой синхронизации.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN syncId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE notebooks ADD COLUMN syncId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE notebooks ADD COLUMN isDeleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE notebooks ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE notebooks ADD COLUMN dirty INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE tags ADD COLUMN syncId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE tags ADD COLUMN isDeleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tags ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tags ADD COLUMN dirty INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE attachments ADD COLUMN syncId TEXT NOT NULL DEFAULT ''")
                for (table in listOf("notes", "notebooks", "tags", "attachments")) {
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_${table}_syncId` ON `$table` (`syncId`)")
                }
            }
        }

        // v2 -> v3 (Этап 3): напоминания к заметкам.
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN reminderAt INTEGER DEFAULT NULL")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN dayKey INTEGER DEFAULT NULL")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN isTask INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE notes ADD COLUMN isCompleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE notes ADD COLUMN taskColor TEXT NOT NULL DEFAULT 'violet'")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notebooks ADD COLUMN isProtected INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE notebooks ADD COLUMN passwordHash TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE notebooks ADD COLUMN passwordSalt TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE notes ADD COLUMN isProtected INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE notes ADD COLUMN passwordHash TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE notes ADD COLUMN passwordSalt TEXT DEFAULT NULL")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN taskElapsedMillis INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE notes ADD COLUMN taskTimerStartedAt INTEGER DEFAULT NULL")
            }
        }

        // v7 -> v8 (Этап 30): таймер обратного отсчёта к задачам.
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN taskCountdownMillis INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE notes ADD COLUMN taskCountdownEndAt INTEGER DEFAULT NULL")
            }
        }

        // v8 -> v9 (Этап 31): отметка времени выполнения задачи для аналитики.
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN taskCompletedAt INTEGER DEFAULT NULL")
                // Старые выполненные задачи получают дату последнего изменения,
                // чтобы сразу попасть в статистику.
                db.execSQL(
                    "UPDATE notes SET taskCompletedAt = updatedAt " +
                        "WHERE isTask = 1 AND isCompleted = 1 AND taskCompletedAt IS NULL"
                )
            }
        }

        // v9 -> v10 (аудит 2026-11-11): индекс по dayKey — виджет-календарь делает
        // диапазонные запросы по дням при каждом обновлении, без индекса это полный
        // скан таблицы заметок.
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_notes_dayKey` ON `notes` (`dayKey`)")
            }
        }

        internal val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE notes ADD COLUMN taskPriority INTEGER NOT NULL DEFAULT 0") }
        }
        internal val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE notes ADD COLUMN textFormat TEXT NOT NULL DEFAULT ''") }
        }

        internal val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE notes ADD COLUMN tablesData TEXT NOT NULL DEFAULT ''") }
        }

        internal val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE attachments ADD COLUMN displayWidthDp INTEGER NOT NULL DEFAULT 240")
                db.execSQL("ALTER TABLE attachments ADD COLUMN displayHeightDp INTEGER NOT NULL DEFAULT 180")
                db.execSQL("ALTER TABLE attachments ADD COLUMN previewRevision INTEGER NOT NULL DEFAULT 0")
            }
        }

        internal val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN isPurged INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE notes ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE notes ADD COLUMN noteColor TEXT NOT NULL DEFAULT ''")
                // Old soft deletions could leave clocks running. Freeze them at deletion time.
                db.execSQL("UPDATE notes SET taskElapsedMillis = MAX(0, taskElapsedMillis) + CASE WHEN taskTimerStartedAt IS NULL THEN 0 ELSE MAX(0, updatedAt - taskTimerStartedAt) END, taskTimerStartedAt = NULL, taskCountdownMillis = CASE WHEN taskCountdownEndAt IS NULL THEN taskCountdownMillis ELSE MAX(0, taskCountdownEndAt - updatedAt) END, taskCountdownEndAt = NULL WHERE isDeleted = 1")
                db.execSQL("CREATE INDEX index_notes_isDeleted_isPurged_isPinned_updatedAt ON notes (isDeleted, isPurged, isPinned, updatedAt)")
            }
        }

        internal val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS index_notes_isDeleted_isPurged_isPinned_updatedAt")
                db.execSQL("CREATE INDEX index_notes_visible_order ON notes (isDeleted, isPurged, isPinned, updatedAt, id, isProtected, notebookId)")
            }
        }

        internal val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN taskDueAt INTEGER DEFAULT NULL")
                db.execSQL("CREATE INDEX index_notes_task_due ON notes (isDeleted, isPurged, isTask, isCompleted, taskDueAt, isProtected, notebookId)")
            }
        }

        internal val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS custom_quotes (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, text TEXT NOT NULL, author TEXT NOT NULL, source TEXT NOT NULL, fingerprint TEXT NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_custom_quotes_fingerprint ON custom_quotes (fingerprint)")
                db.execSQL("CREATE TABLE IF NOT EXISTS quote_library_state (id INTEGER NOT NULL, pinnedQuoteId INTEGER, PRIMARY KEY(id), FOREIGN KEY(pinnedQuoteId) REFERENCES custom_quotes(id) ON UPDATE NO ACTION ON DELETE SET NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_quote_library_state_pinnedQuoteId ON quote_library_state (pinnedQuoteId)")
            }
        }

        internal val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // No existing note, relation, password or attachment is rewritten.
                db.execSQL("ALTER TABLE notebooks ADD COLUMN isDiary INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context, "notessync.db").also { database ->
                    // A notification after actual DB changes, with no polling or service.
                    database.invalidationTracker.addObserver(object : InvalidationTracker.Observer(
                        "notes", "notebooks", "tags", "note_tag_cross_ref", "attachments", "custom_quotes", "quote_library_state"
                    ) {
                        override fun onInvalidated(tables: Set<String>) { BackupManager.noteChanged() }
                    })
                    instance = database
                }
            }

        internal fun openForRestore(context: Context, path: String): AppDatabase = build(context, path)

        private fun build(context: Context, path: String): AppDatabase = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    path
                ).addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                        MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                        MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19
                    ).addCallback(LegacyIdentityRepair).build()

        // Этап 39: закрыть и сбросить синглтон перед подменой файла БД
        // при восстановлении бэкапа. Следующий get() откроет базу заново.
        fun closeInstance() {
            synchronized(this) {
                com.notessync.app.tasks.running.RunningTaskNotifications.stop()
                instance?.close()
                instance = null
            }
        }
    }
}
