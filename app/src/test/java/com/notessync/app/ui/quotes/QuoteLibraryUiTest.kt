package com.notessync.app.ui.quotes

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.data.quotes.*
import com.notessync.app.i18n.UiLanguage
import com.notessync.app.settings.*
import com.notessync.app.ui.screens.QuotesScreen
import com.notessync.app.ui.theme.NoteSyncTheme
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode
import java.io.File

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "ru-w320dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class QuoteLibraryUiTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = RuntimeEnvironment.getApplication()
    private val settings get() = SettingsRepository.get(app)
    private lateinit var db: AppDatabase
    private lateinit var view: View
    @Before fun before() {
        AppDatabase.closeInstance(); app.deleteDatabase("notessync.db"); db = AppDatabase.get(app)
        settings.apply { setUiLanguage(UiLanguage.RUSSIAN); setThemeMode(ThemeMode.LIGHT); setFontScale(SettingsRepository.FONT_NORMAL); setAppFont(AppFont.SYSTEM); setBackupFrequency(BackupFrequency.MANUAL) }
    }
    @After fun after() { AppDatabase.closeInstance(); settings.setUiLanguage(UiLanguage.RUSSIAN) }
    private fun count() = runBlocking { db.customQuoteDao().count() }
    private fun screenshot(name: String, tag: String) {
        val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(bounds.width.toInt(), bounds.height.toInt(), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap); canvas.translate(-bounds.left, -bounds.top); view.draw(canvas)
            File("build/reports/quotes-preview/$name.png").apply { parentFile?.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    @Test fun `drawer quote stays fixed while notebooks scroll and leaves navigation usable with large fonts`() {
        var height by mutableStateOf(480.dp); var scale by mutableFloatStateOf(1f); var enabled by mutableStateOf(true); var clicks = 0; var density = 1f
        val text = "Длинная цитата с автором. ".repeat(40)
        compose.setContent {
            view = LocalView.current
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, scale)) {
                density = base.density
                NoteSyncTheme { Surface(Modifier.requiredSize(320.dp, height).testTag("quote-drawer-viewport")) {
                    PinnedDrawerContent(DrawerQuoteAppearance(enabled = enabled, size = 32f), 0, text, true, { clicks++ }) {
                        repeat(30) { Text("Блокнот $it", Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(12.dp)) }
                    }
                } }
            }
        }
        val before = compose.onNodeWithTag("drawer-pinned-quote").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("Блокнот 29").performScrollTo().assertIsDisplayed()
        assertEquals(before, compose.onNodeWithTag("drawer-pinned-quote").fetchSemanticsNode().boundsInRoot)
        screenshot("drawer-320", "quote-drawer-viewport")
        compose.onNodeWithTag("drawer-pinned-quote").performClick(); compose.runOnIdle { assertEquals(1, clicks); height = 320.dp; scale = 2f }
        compose.onNodeWithTag("drawer-pinned-quote").assertIsDisplayed()
        val nav = compose.onNodeWithTag("drawer-navigation").fetchSemanticsNode().boundsInRoot
        val quote = compose.onNodeWithTag("drawer-pinned-quote").fetchSemanticsNode().boundsInRoot
        assertTrue(nav.height >= 96 * density - 1); assertTrue(quote.height <= 180 * density + 1); assertTrue(quote.top >= nav.bottom - 1)
        compose.onNodeWithText(text).assertIsDisplayed()
        screenshot("drawer-large-font-320", "quote-drawer-viewport")
        compose.runOnIdle { enabled = false }
        compose.onNodeWithTag("drawer-pinned-quote").assertDoesNotExist()
    }
    @Test fun `screen creates edits pins and deletes own quotes without changing existing notes`() = runBlocking {
        val note = db.noteDao().upsert(NoteEntity(title = "Keep note"))
        compose.setContent { view = LocalView.current; NoteSyncTheme { Box(Modifier.requiredSize(320.dp, 760.dp).testTag("quotes-viewport")) { QuotesScreen({}) } } }
        compose.onNodeWithContentDescription("Добавить цитату").performClick()
        compose.onNodeWithText("Текст цитаты").performTextInput("Мой пример")
        compose.onNodeWithText("Автор (необязательно)").performScrollTo().performTextInput("Автор")
        compose.onNodeWithText("Источник (необязательно)").performScrollTo().performTextInput("Книга")
        compose.onNodeWithText("Сохранить").performClick(); compose.waitUntil { count() == 1 }
        compose.onNodeWithContentDescription("Действия с цитатой").performScrollTo().performClick()
        compose.onNodeWithText("Закрепить в меню").performClick()
        compose.waitUntil { runBlocking { db.customQuoteDao().pinned() != null } }
        screenshot("library-320", "quotes-viewport")
        compose.onNodeWithContentDescription("Действия с цитатой").performScrollTo().performClick(); compose.onNodeWithText("Редактировать").performClick()
        compose.onNodeWithText("Текст цитаты").performTextReplacement("Изменено")
        compose.onNodeWithText("Сохранить").performClick()
        compose.waitUntil { runBlocking { db.customQuoteDao().pinned()?.text == "Изменено" } }
        compose.onNodeWithContentDescription("Действия с цитатой").performScrollTo().performClick(); compose.onNodeWithText("Удалить").performClick()
        compose.onNodeWithText("Отмена").performClick(); compose.runOnIdle { assertEquals(1, count()) }
        compose.onNodeWithContentDescription("Действия с цитатой").performScrollTo().performClick(); compose.onNodeWithText("Удалить").performClick()
        compose.onNodeWithText("Удалить").performClick(); compose.waitUntil { count() == 0 }
        assertNull(db.customQuoteDao().pinned()); assertEquals("Keep note", db.noteDao().getById(note)!!.title)
    }
    @Test fun `actual file preview cancellation writes nothing and import rechecks a concurrent duplicate`() = runBlocking {
        lateinit var model: QuoteLibraryUi; var completed = 0
        compose.setContent { NoteSyncTheme { Surface {
            val host = remember { SnackbarHostState() }; model = rememberQuoteLibraryUi(host)
            QuoteLibraryDialogs(model, onUpdated = { completed++ })
        } } }
        val file = File(app.cacheDir, "quotes.json").apply { writeText("[{\"text\":\"One\"},{\"text\":\"ONE\"},{\"text\":\"Two\"}]") }
        compose.runOnIdle { model.load(Uri.fromFile(file)) }; compose.waitUntil { model.preview != null && !model.busy }
        compose.onNodeWithText("Новых: 2. Дубликатов: 1.").assertExists(); assertEquals(0, count())
        compose.onNodeWithText("Отмена").performClick(); compose.runOnIdle { assertNull(model.preview); assertEquals(0, count()) }
        compose.runOnIdle { model.load(Uri.fromFile(file)) }; compose.waitUntil { model.preview != null && !model.busy }
        QuoteLibrary(db).save(null, QuoteDraft("Two"))
        compose.onNodeWithText("Загрузить").performClick(); compose.waitUntil { completed == 1 && !model.busy }
        assertEquals(2, count()); assertNull(model.preview)
    }
    @Test fun `appearance changes keep the library pin but editing manual text explicitly clears it`() = runBlocking {
        val library = QuoteLibrary(db); val id = library.save(null, QuoteDraft("Pinned")); library.pin(db.customQuoteDao().get(id)!!)
        settings.setDrawerQuoteAppearance(DrawerQuoteAppearance(customText = "Old manual"))
        var show by mutableStateOf(true); var closed = 0
        compose.setContent { NoteSyncTheme {
            val state by settings.state.collectAsState()
            if (show) DrawerQuoteSettingsDialog(state.drawerQuote, settings) { show = false; closed++ }
        } }
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).performSemanticsAction(SemanticsActions.SetProgress) { it(24f) }
        compose.onNodeWithText("Сохранить").performClick(); compose.waitUntil { closed == 1 }
        assertEquals(id, db.customQuoteDao().pinned()!!.id); assertEquals(24f, settings.state.value.drawerQuote.size)
        compose.runOnIdle { show = true }
        compose.onNodeWithText("Свой текст").performScrollTo().performTextReplacement("New manual")
        compose.onNodeWithText("Сохранить").performClick(); compose.waitUntil { closed == 2 }
        assertNull(db.customQuoteDao().pinned()); assertEquals("New manual", settings.state.value.drawerQuote.customText)
        assertEquals("Pinned", db.customQuoteDao().get(id)!!.text)
    }
    @Test fun `English editor controls preserve user text and do not translate quote content`() {
        settings.setUiLanguage(UiLanguage.ENGLISH); var saved: QuoteDraft? = null
        compose.setContent { NoteSyncTheme { QuoteEditorDialog(QuoteDraft("Текст пользователя", "Автор"), false, null, { saved = it }, {}) } }
        compose.onNodeWithText("Quote text").assertExists(); compose.onNodeWithText("Author (optional)").assertExists()
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { assertEquals(QuoteDraft("Текст пользователя", "Автор"), saved) }
    }
}
