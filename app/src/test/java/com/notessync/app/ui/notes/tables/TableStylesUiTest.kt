package com.notessync.app.ui.notes.tables

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.i18n.UiLanguage
import com.notessync.app.notes.tables.*
import com.notessync.app.notes.text.FormatKind
import com.notessync.app.settings.*
import com.notessync.app.ui.notes.NoteEditorViewModel
import com.notessync.app.ui.theme.NoteSyncTheme
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,qualifiers="ru-w320dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TableStylesUiTest {
    @get:Rule val compose=createComposeRule()
    private val store=ViewModelStore()
    private val app get()=RuntimeEnvironment.getApplication()
    private val settings get()=SettingsRepository.get(app)
    private lateinit var vm:NoteEditorViewModel
    private var shownId by mutableStateOf<String?>(null)
    private val id get() = requireNotNull(shownId)
    @Before fun before() {
        AppDatabase.closeInstance();app.deleteDatabase("notessync.db")
        SettingsRepository::class.java.getDeclaredField("instance").apply { isAccessible=true;set(null,null) }
        settings.apply { setUiLanguage(UiLanguage.RUSSIAN);setEditorHistory(true);setBackupFrequency(BackupFrequency.MANUAL);setFontScale(1f) }
    }
    @After fun after() {
        store.clear();AppDatabase.closeInstance()
        settings.apply {setUiLanguage(UiLanguage.RUSSIAN);setFontScale(1f)}
    }
    private fun content(large:Boolean=false,keyboard:Boolean=false) {
        compose.setContent {
            val state by settings.state.collectAsState()
            vm=remember { NoteEditorViewModel(app,SavedStateHandle()).also { store.put("editor",it) } }
            NoteSyncTheme(darkTheme=large,dynamicColor=false,fontScale=state.fontScale) {
                Box(Modifier.requiredSize(320.dp,640.dp)) {
                    if(shownId != null) TableEditorDialog(id,vm,remember { SnackbarHostState() },{},
                        imeInsets=if(keyboard) WindowInsets(bottom=with(LocalDensity.current){240.dp.roundToPx()}) else WindowInsets(0,0,0,0))
                }
            }
        }
        compose.waitUntil(10_000) { compose.waitForIdle();vm.isReady }
        compose.runOnIdle { shownId=vm.addTable(3,3)!! }
        compose.runOnIdle { vm.editTableCell(TableCellRef(id,0,0),"Текст 😀") }
        compose.onNodeWithTag("cell-0-0").assertIsDisplayed()
    }
    private fun menu()=compose.onNodeWithTag("table-format-open").performClick()
    private fun scope(label:String) {
        compose.onNodeWithTag("table-format-scope").performClick()
        compose.onNode(hasText(label) and hasAnyAncestor(isPopup())).performClick()
    }
    private fun screenshot(name:String) { compose.runOnIdle {
        val view=ShadowDialog.getLatestDialog().window!!.decorView
        val b=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(b))
        File("build/reports/table-style-preview/$name.png").apply {parentFile?.mkdirs()}.outputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it)}
        b.recycle()
    } }
    @Test fun `scope selection applies whole row font and undo redo reset are independent from text`() {
        content();compose.onNodeWithTag("cell-0-0").performClick();scope("Строка 1")
        menu();compose.onNodeWithText("Шрифт:",substring=true).performClick();compose.onNodeWithText("Моноширинный").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(3,vm.tables!!.find(id)!!.styles.size) }
        compose.onNodeWithContentDescription("Отменить").performClick()
        compose.runOnIdle {assertTrue(vm.tables!!.find(id)!!.styles.isEmpty())}
        compose.onNodeWithContentDescription("Повторить").performClick()
        compose.runOnIdle {assertEquals(3,vm.tables!!.find(id)!!.styles.size)}
        menu();compose.onNodeWithText("Сбросить оформление").performScrollTo().performClick()
        compose.runOnIdle {assertTrue(vm.tables!!.find(id)!!.styles.isEmpty());assertEquals("Текст 😀",vm.tables!!.find(id)!!.cells[0][0])}
    }
    @Test fun `cell colors alignment and mixed row controls retain selected state`() {
        content();compose.onNodeWithTag("cell-0-0").performClick()
        menu();compose.onNodeWithText("Цвет текста").performClick();compose.onNodeWithText("Красный").performScrollTo().performClick()
        menu();compose.onNodeWithText("Выравнивание").performClick();compose.onNodeWithText("По центру").performClick()
        compose.runOnIdle {
            val style=vm.tables!!.find(id)!!.styleAt(0,0)
            assertEquals("FFE53935",style[FormatKind.COLOR]);assertEquals("CENTER",style[FormatKind.ALIGN])
        }
        screenshot("cell-320")
        scope("Строка 1");menu();compose.onNodeWithText("Разное оформление").performScrollTo().assertIsDisplayed()
    }
    @Test fun `English large text and keyboard keep cell and history controls visible`() {
        settings.setUiLanguage(UiLanguage.ENGLISH);settings.setFontScale(SettingsRepository.FONT_LARGE)
        content(large=true,keyboard=true)
        compose.runOnIdle {vm.formatTableCells(TableCellRef(id,0,0),TableFormatScope.CELL,FormatKind.SIZE,"48")}
        compose.onNodeWithTag("cell-0-0").performClick().performTextInput(" more")
        compose.onNodeWithContentDescription("Undo").assertIsDisplayed()
        compose.onNodeWithTag("table-format-scope").assertIsDisplayed()
        val cell=compose.onNodeWithTag("cell-0-0").fetchSemanticsNode().boundsInRoot
        val viewport=compose.onNode(isDialog()).fetchSemanticsNode().boundsInRoot
        assertTrue(cell.height>=64);assertTrue(cell.bottom<=viewport.bottom-240)
        screenshot("large-ime-320")
    }
    @Test fun `geometry is collapsed until requested and focus changes update current scope`() {
        content();compose.onNodeWithContentDescription("Шире").assertDoesNotExist()
        compose.onNodeWithTag("table-geometry-toggle").performClick()
        compose.onNodeWithContentDescription("Шире").assertIsDisplayed().performClick()
        compose.runOnIdle {assertEquals(176,vm.tables!!.find(id)!!.columnWidths[0])}
        compose.onNodeWithTag("table-geometry-toggle").performClick()
        compose.onNodeWithTag("cell-1-0").performClick()
        compose.onNodeWithTag("table-format-scope").assert(hasText("Ячейка 2:1"))
    }
}
