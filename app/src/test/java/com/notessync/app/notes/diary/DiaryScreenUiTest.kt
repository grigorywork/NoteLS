package com.notessync.app.notes.diary

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.i18n.UiLanguage
import com.notessync.app.security.*
import com.notessync.app.settings.*
import com.notessync.app.ui.screens.DiaryScreen
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
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "ru-w320dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DiaryScreenUiTest {
    @get:Rule val compose = createComposeRule()
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val db get() = AppDatabase.get(app)
    private val models = ViewModelStore()
    private lateinit var drawingView: View
    private val opened = AtomicLong()
    private val keyboardDp = mutableIntStateOf(0)
    private var density = 1f
    @Before fun before() {
        AppDatabase.closeInstance(); app.deleteDatabase("notessync.db"); DiarySession.lock(); ProtectionSession.lockAll()
        SettingsRepository.get(app).apply { setUiLanguage(UiLanguage.RUSSIAN); setBackupFrequency(BackupFrequency.MANUAL) }
    }
    @After fun after() { models.clear(); DiarySession.lock(); ProtectionSession.lockAll(); AppDatabase.closeInstance() }
    private fun await(condition: () -> Boolean) {
        try { compose.waitUntil(15_000) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(100)); compose.waitForIdle(); condition()
        } } catch (failure: Throwable) {
            snapshot("failure")
            val model = models["diary"] as? DiaryViewModel
            throw AssertionError("Diary state: books=${model?.books?.value?.map { it.id }} message=${model?.message?.value}\n" +
                compose.onRoot(useUnmergedTree = true).printToString(), failure)
        }
    }
    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
    private fun content(): DiaryViewModel {
        val vm = DiaryViewModel(app).also { models.put("diary", it) }
        compose.setContent {
            drawingView = LocalView.current
            density = LocalDensity.current.density
            val keyboardPixels = with(LocalDensity.current) { keyboardDp.intValue.dp.roundToPx() }
            NoteSyncTheme(darkTheme = true, dynamicColor = false) {
                Box(Modifier.requiredSize(320.dp, 760.dp).testTag("diary-viewport")) {
                    DiaryScreen({}, { opened.set(it) }, vm, imeInsets = WindowInsets(bottom = keyboardPixels))
                }
            }
        }
        await { vm.books.value != null }
        return vm
    }
    private fun snapshot(name: String) {
        compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(drawingView.width, drawingView.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap); canvas.drawColor(Color.BLACK); drawingView.draw(canvas)
            val output = File("build/reports/diary-preview/$name.png").apply { parentFile!!.mkdirs() }
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val pixels = IntArray(bitmap.width * bitmap.height); bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue(pixels.all { (it ushr 24) == 255 }); bitmap.recycle()
        }
    }

    private fun enter() {
        compose.onNodeWithTag("diary-enter").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("diary-enter").assertIsEnabled()
        val field = compose.onNodeWithTag("diary-enter").fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("diary-viewport").fetchSemanticsNode().boundsInRoot
        assertTrue("button=$field keyboardTop=${viewport.bottom - keyboardDp.intValue*density}",
            field.bottom <= viewport.bottom - keyboardDp.intValue*density + 1f)
        compose.onNodeWithTag("diary-enter").performClick()
    }

    @Test fun `setup remains usable above a keyboard rejects mismatch creates private entries and locks previews`() {
        keyboardDp.intValue = 260
        val vm = content()
        compose.onNodeWithTag("diary-password").performScrollTo().performTextReplacement("fixture-diary-password")
        compose.onNodeWithTag("diary-confirm").performScrollTo().performTextReplacement("different")
        enter()
        await { exists(hasText("Пароли должны совпадать: 4–128 символов")) }
        runBlocking { assertTrue(db.notebookDao().getDiaries().isEmpty()) }
        compose.onNodeWithTag("diary-password").performScrollTo().performTextReplacement("fixture-diary-password")
        compose.onNodeWithTag("diary-confirm").performScrollTo().performTextReplacement("fixture-diary-password")
        enter()
        await { exists(hasContentDescription("Новая запись дневника")) }
        compose.onNodeWithContentDescription("Новая запись дневника").performClick()
        await { opened.get() > 0 }
        val note = runBlocking { db.noteDao().getById(opened.get())!! }
        assertEquals(vm.book.value!!.id, note.notebookId); assertFalse(note.isTask); assertNotNull(note.dayKey)
        compose.onNodeWithContentDescription("Заблокировать дневник").performClick()
        await { exists(hasTestTag("diary-password")) }
        compose.onNodeWithTag("diary-row-${note.id}").assertDoesNotExist()
        compose.runOnIdle { keyboardDp.intValue = 0 }
        compose.waitForIdle()
        snapshot("password-320")
    }

    @Test fun `separately protected entry exposes no title or body and requires its own verified password`() {
        val note = runBlocking {
            val book = PrivateDiary.create(db, PasswordProtector.create("journal-password"))
            DiarySession.register(book.id); ProtectionSession.unlockNotebook(book.id)
            val password = PasswordProtector.create("entry-password")
            val raw = NoteEntity(title = "SECRET ENTRY TITLE", content = "SECRET ENTRY BODY", notebookId = book.id,
                isTask = true, isProtected = true, passwordHash = password.hash, passwordSalt = password.salt)
            raw.copy(id = db.noteDao().upsert(raw))
        }
        content(); await { exists(hasTestTag("diary-row-${note.id}")) }
        compose.onNodeWithText(note.title).assertDoesNotExist(); compose.onNodeWithText(note.content).assertDoesNotExist()
        compose.onNodeWithTag("diary-row-${note.id}").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("wrong-password")
        compose.onNodeWithText("Открыть").performClick()
        await { exists(hasText("Неверный пароль или запись изменилась")) }
        assertEquals(0L, opened.get()); assertFalse(ProtectionSession.isUnlocked(note))
        compose.onNodeWithTag("diary-row-${note.id}").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("entry-password")
        compose.onNodeWithText("Открыть").performClick()
        await { opened.get() == note.id }
        assertTrue(ProtectionSession.isUnlocked(note)); assertEquals(note, runBlocking { db.noteDao().getById(note.id) })
    }
}
