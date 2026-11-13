package com.notessync.app.widget

import android.app.Application
import android.appwidget.AppWidgetManager
import android.os.Looper
import android.view.View
import android.widget.TextView
import com.notessync.app.R
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.i18n.UiLanguage
import com.notessync.app.security.ProtectionSession
import com.notessync.app.settings.SettingsRepository
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CompactWidgetUpdatesTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @Before fun before() = runBlocking {
        CompactWidgetUpdates.stop()?.join(); AppDatabase.closeInstance(); app.deleteDatabase("notessync.db")
        SettingsRepository::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, null) }
        SettingsRepository.get(app).setUiLanguage(UiLanguage.RUSSIAN); ProtectionSession.lockAll()
    }
    @After fun after() = runBlocking {
        CompactWidgetUpdates.stop()?.join()
        SettingsRepository.get(app).setUiLanguage(UiLanguage.RUSSIAN); shadowOf(Looper.getMainLooper()).idle()
        AppDatabase.closeInstance(); ProtectionSession.lockAll()
        SettingsRepository::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, null) }
        Unit
    }
    private suspend fun refresh() {
        val done = CompletableDeferred<Unit>()
        CompactWidgetUpdates.request(app) { done.complete(Unit) }
        withTimeout(10_000) { done.await() }
    }
    private suspend fun await(condition: () -> Boolean) = withTimeout(10_000) { while (!condition()) delay(10) }
    @Test fun `Room events retain the last edit and remove protected text and stale actions without a periodic refresh`() = runBlocking {
        val db = AppDatabase.get(app); val note = NoteEntity(title = "Initial").let { it.copy(id = db.noteDao().upsert(it)) }
        val manager = shadowOf(AppWidgetManager.getInstance(app)); val id = manager.createWidget(CompactWidgetProvider::class.java, R.layout.widget_compact)
        refresh()
        fun row() = manager.getViewFor(id).findViewById<TextView>(R.id.compact_row_0)
        assertEquals("Initial", row().text.toString())
        db.noteDao().update(note.copy(title = "First edit", updatedAt = note.updatedAt + 1))
        db.noteDao().update(note.copy(title = "Final edit", updatedAt = note.updatedAt + 2))
        await { row().text.toString() == "Final edit" }; delay(350); assertEquals("Final edit", row().text.toString())
        SettingsRepository.get(app).setUiLanguage(UiLanguage.ENGLISH)
        await { manager.getViewFor(id).findViewById<View>(R.id.compact_add).contentDescription == "Quick task" }
        ProtectionSession.unlockTask(note.syncId)
        db.noteDao().update(note.copy(title = "Protected", isProtected = true, updatedAt = note.updatedAt + 3))
        await { manager.getViewFor(id).findViewById<View>(R.id.compact_empty).visibility == View.VISIBLE }
        assertEquals("", row().text.toString()); assertFalse(row().performClick())
        assertEquals("No entries yet", manager.getViewFor(id).findViewById<TextView>(R.id.compact_empty).text.toString())
    }
    @Test fun `stopping the installed widget observer halts updates and a later bind reads the latest state`() = runBlocking {
        val db = AppDatabase.get(app); val note = NoteEntity(title = "Before stop").let { it.copy(id = db.noteDao().upsert(it)) }
        val manager = shadowOf(AppWidgetManager.getInstance(app)); val id = manager.createWidget(CompactWidgetProvider::class.java, R.layout.widget_compact)
        refresh(); CompactWidgetUpdates.stop()?.join(); assertNull(CompactWidgetUpdates.stop())
        db.noteDao().update(note.copy(title = "After restart", updatedAt = note.updatedAt + 1)); delay(350)
        assertEquals("Before stop", manager.getViewFor(id).findViewById<TextView>(R.id.compact_row_0).text.toString())
        refresh(); assertEquals("After restart", manager.getViewFor(id).findViewById<TextView>(R.id.compact_row_0).text.toString())
        app.resources.getXml(R.xml.widget_compact_info).use { parser ->
            while (parser.eventType != org.xmlpull.v1.XmlPullParser.START_TAG) parser.next()
            assertEquals("0", parser.getAttributeValue("http://schemas.android.com/apk/res/android", "updatePeriodMillis"))
        }
    }
}
