package com.notessync.app.ui.analytics

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.data.local.entity.NotebookEntity
import com.notessync.app.i18n.UiLanguage
import com.notessync.app.settings.SettingsRepository
import com.notessync.app.ui.screens.AnalyticsScreen
import com.notessync.app.ui.theme.NoteSyncTheme
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,qualifiers="ru-w320dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnalyticsPeriodsUiTest {
    @get:Rule val compose=createComposeRule()
    private val app:Application get()=RuntimeEnvironment.getApplication()
    private val settings get()=SettingsRepository.get(app)
    private val store=ViewModelStore()
    private lateinit var db:AppDatabase
    private lateinit var vm:AnalyticsViewModel
    private lateinit var drawingView:View
    private val now=LocalDate.of(2026,11,27).atTime(12,0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private var opened:Long?=null
    @Before fun before() {
        AppDatabase.closeInstance();app.deleteDatabase("notessync.db");db=AppDatabase.get(app)
        SettingsRepository::class.java.getDeclaredField("instance").apply {isAccessible=true;set(null,null)}
        settings.setUiLanguage(UiLanguage.RUSSIAN);settings.setFontScale(SettingsRepository.FONT_NORMAL)
        vm=AnalyticsViewModel(app){now}.also {store.put("analytics",it)}
    }
    @After fun after() {store.clear();compose.waitForIdle();AppDatabase.closeInstance();settings.setUiLanguage(UiLanguage.RUSSIAN);settings.setFontScale(SettingsRepository.FONT_NORMAL)}
    private fun content() {compose.setContent {
        drawingView=LocalView.current
        val state by settings.state.collectAsState()
        NoteSyncTheme(darkTheme=state.fontScale>1f,dynamicColor=false,fontScale=state.fontScale) {
            Box(Modifier.requiredSize(320.dp,760.dp)) {AnalyticsScreen({}, {opened=it},vm)}
        }
    } }
    private fun await(check:()->Boolean) {compose.waitUntil(15_000) {
        ShadowSystemClock.advanceBy(Duration.ofMillis(100));compose.waitForIdle();check()
    } }
    private fun awaitCount(tag:String,count:Int) = await {
        // Clickable rows/buttons merge their count into the tagged semantics node.
        compose.onAllNodes(hasText(count.toString()) and
            (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag)))).fetchSemanticsNodes().isNotEmpty()
    }
    private fun screenshot(name:String,dialog:Boolean=false) {compose.runOnIdle {
        val view=if(dialog) ShadowDialog.getLatestDialog().window!!.decorView else drawingView
        val b=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(b))
        File("build/reports/analytics-preview/$name.png").apply {parentFile?.mkdirs()}.outputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it)}
        b.recycle()
    } }
    @Test fun `period changes and task pages show exact counts and clamp after deletions`() {
        val ids=runBlocking {(1..25).map {db.noteDao().upsert(NoteEntity(title="Completed $it",isTask=true,isCompleted=true,taskCompletedAt=now))}}
        runBlocking {
            repeat(2){db.noteDao().upsert(NoteEntity(title="Previous $it",isTask=true,isCompleted=true,taskCompletedAt=now-8*86_400_000L))}
            db.noteDao().upsert(NoteEntity(title="Future",isTask=true,isCompleted=true,taskCompletedAt=now+3_600_000))
        }
        content();awaitCount("analytics-current",25)
        compose.onNodeWithTag("analytics-current").performScrollTo();screenshot("periods-320")
        compose.onNodeWithTag("analytics-period-month").performScrollTo().performClick();awaitCount("analytics-current",27)
        compose.onNodeWithTag("analytics-period-week").performScrollTo().performClick();awaitCount("analytics-current",25)
        compose.onNodeWithTag("analytics-current").performScrollTo().performClick()
        await {compose.onAllNodesWithTag("analytics-task-${ids.last()}").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Всего задач: 25").assertIsDisplayed()
        compose.onNode(hasText("Future") and hasAnyAncestor(isDialog())).assertDoesNotExist()
        compose.onNodeWithContentDescription("Следующая страница").performClick()
        await {compose.onAllNodesWithText("Страница 2 из 2").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("analytics-task-${ids.first()}").assertExists()
        runBlocking {ids.take(5).forEach {id ->db.noteDao().update(db.noteDao().getById(id)!!.copy(isDeleted=true))}}
        await {compose.onAllNodesWithText("Страница 1 из 1").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Всего задач: 20").assertIsDisplayed()
    }
    @Test fun `overdue remains usable without completed tasks and opening uses the current row`() {
        val id=runBlocking {db.noteDao().upsert(NoteEntity(title="Due task",isTask=true,taskDueAt=now-1))}
        runBlocking {db.noteDao().upsert(NoteEntity(title="Reminder only",isTask=true,reminderAt=now-1000))}
        content();awaitCount("analytics-overdue",1)
        compose.onNodeWithTag("analytics-overdue").performScrollTo().performClick()
        await {compose.onAllNodesWithTag("analytics-task-$id").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Всего задач: 1").assertIsDisplayed();screenshot("overdue-320",true)
        compose.onNodeWithTag("analytics-task-$id").performClick();await {opened==id}
    }
    @Test fun `diary conversion removes cached list titles and filter choices without exposing old rows`() {
        val book=runBlocking {db.notebookDao().upsert(NotebookEntity(name="Work"))}
        val sensitive=runBlocking {db.noteDao().upsert(NoteEntity(title="Sensitive",notebookId=book,isTask=true,isCompleted=true,taskCompletedAt=now))}
        val public=runBlocking {db.noteDao().upsert(NoteEntity(title="Public",isTask=true,isCompleted=true,taskCompletedAt=now))}
        content();awaitCount("analytics-current",2)
        compose.onNodeWithTag("analytics-current").performScrollTo().performClick()
        await {compose.onAllNodesWithTag("analytics-task-$sensitive").fetchSemanticsNodes().isNotEmpty()}
        runBlocking {db.notebookDao().update(db.notebookDao().getById(book)!!.copy(isDiary=true))}
        await {vm.state.value.excludedNotebookIds.contains(book) && compose.onAllNodesWithTag("analytics-task-$public").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("analytics-task-$sensitive").assertDoesNotExist()
        compose.onNodeWithText("Закрыть").performClick()
        compose.onNodeWithTag("analytics-filter").performScrollTo().performClick()
        compose.onNodeWithTag("analytics-scope-book-$book").assertDoesNotExist()
    }
    @Test fun `English large font keeps period and list actions reachable on a narrow display`() {
        settings.setUiLanguage(UiLanguage.ENGLISH);settings.setFontScale(SettingsRepository.FONT_LARGE)
        runBlocking {db.noteDao().upsert(NoteEntity(title="Overdue",isTask=true,taskDueAt=now-1000))}
        content();awaitCount("analytics-overdue",1)
        compose.onNodeWithTag("analytics-period-month").performScrollTo().performClick()
        await {vm.state.value.period==AnalyticsPeriod.MONTH && !vm.state.value.loading}
        compose.onNodeWithTag("analytics-overdue").performScrollTo().performClick()
        await {compose.onAllNodesWithText("Total tasks: 1").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Close").assertIsDisplayed();compose.onNodeWithText("Page 1 of 1").assertIsDisplayed()
        screenshot("overdue-large-en-320",true)
    }
}
