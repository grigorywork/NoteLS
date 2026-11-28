package com.notessync.app.settings

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelStore
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.i18n.UiLanguage
import com.notessync.app.ui.calendar.CalendarViewModel
import com.notessync.app.ui.screens.CalendarScreen
import com.notessync.app.ui.theme.NoteSyncTheme
import com.notessync.app.ui.theme.appFontFamily
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,qualifiers="ru-w320dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CalendarTypographyCalendarUiTest {
    @get:Rule val compose=createComposeRule()
    private val app get()=RuntimeEnvironment.getApplication()
    private val models=ViewModelStore()
    @Before fun before() {
        AppDatabase.closeInstance();app.deleteDatabase("notessync.db")
        SettingsRepository::class.java.getDeclaredField("instance").apply{isAccessible=true;set(null,null)}
        SettingsRepository.get(app).apply { setUiLanguage(UiLanguage.RUSSIAN);setAppFont(AppFont.MONO);setCalendarTypography(AppFont.SERIF,CalendarTextSize.LARGE) }
    }
    @After fun after() { models.clear();AppDatabase.closeInstance() }
    private fun layout(text:String):TextLayoutResult {
        val results=mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }
    @Test fun `real calendar applies font to weekdays and dense entry preview then observes reset`() {
        val date=LocalDate.now()
        runBlocking { AppDatabase.get(app).noteDao().upsert(NoteEntity(title="Calendar text sample",dayKey=date.toEpochDay())) }
        val vm=CalendarViewModel(app).also { models.put("calendar",it) }
        compose.setContent { NoteSyncTheme { Box(Modifier.requiredSize(320.dp,640.dp)) { CalendarScreen({}, {_,_->},{_,_->},vm) } } }
        compose.waitUntil(15_000) { compose.waitForIdle();compose.onAllNodesWithText("Calendar text sample").fetchSemanticsNodes().size>=2 }
        val weekday=layout("Пн")
        assertEquals(appFontFamily(AppFont.SERIF),weekday.layoutInput.style.fontFamily)
        assertEquals((9*1.15f).sp,layoutPreview().layoutInput.style.fontSize)
        compose.runOnIdle { SettingsRepository.get(app).setCalendarTypography(null,CalendarTextSize.NORMAL) }
        compose.waitForIdle()
        assertEquals(9.sp,layoutPreview().layoutInput.style.fontSize)
        assertEquals(appFontFamily(AppFont.MONO),layout("Пн").layoutInput.style.fontFamily)
    }
    private fun layoutPreview():TextLayoutResult {
        // The other occurrence is the agenda title; the dense cell uses labelSmall.
        val results=mutableListOf<TextLayoutResult>()
        compose.onAllNodesWithText("Calendar text sample")[0].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }
}
