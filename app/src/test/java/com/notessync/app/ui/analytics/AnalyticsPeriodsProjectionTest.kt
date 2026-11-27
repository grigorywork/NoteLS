package com.notessync.app.ui.analytics

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.data.local.entity.NotebookEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AnalyticsPeriodsProjectionTest {
    private val app:Application get()=RuntimeEnvironment.getApplication()
    private lateinit var db:AppDatabase
    private val store=ViewModelStore()
    @Before fun before() {AppDatabase.closeInstance();app.deleteDatabase("notessync.db");db=AppDatabase.get(app)}
    @After fun after() {store.clear();AppDatabase.closeInstance()}
    @Test fun `scalar projections mask private notebook names exclude diary and retain deadline identity without writes`() = runBlocking {
        val public=db.notebookDao().upsert(NotebookEntity(name="Public"))
        val private=db.notebookDao().upsert(NotebookEntity(name="Private name",isProtected=true))
        val diary=db.notebookDao().upsert(NotebookEntity(name="Diary name",isDiary=true))
        val source=NoteEntity(title="😀".repeat(600),content="body".repeat(20000),tablesData="opaque tables",notebookId=public,isTask=true,taskDueAt=100,syncId="public-row")
        val id=db.noteDao().upsert(source)
        db.noteDao().upsert(source.copy(notebookId=private,syncId="private-row"));db.noteDao().upsert(source.copy(notebookId=diary,syncId="diary-row"))
        db.noteDao().upsert(source.copy(isProtected=true,syncId="locked-row"));db.noteDao().upsert(source.copy(isDeleted=true,syncId="trash-row"))
        db.noteDao().upsert(source.copy(isPurged=true,syncId="purge-row"))
        val rows=db.noteDao().observeForAnalytics().first()
        assertEquals(listOf(id),rows.map {it.id});assertEquals("public-row",rows.single().syncId)
        assertEquals(100L,rows.single().taskDueAt);assertEquals(512,rows.single().title.length)
        assertFalse(rows.single().title.last().isHighSurrogate())
        val books=db.notebookDao().observeForAnalytics().first()
        assertEquals("",books.first {it.id==private}.name);assertEquals("",books.first {it.id==diary}.name)
        assertTrue(books.first {it.id==diary}.isDiary)
        assertEquals(source.copy(id=id),db.noteDao().getById(id))
    }
    @Test fun `past reminder alone never creates overdue task while a real deadline does`() = runBlocking {
        val now=LocalDate.of(2026,11,27).atTime(12,0).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
        db.noteDao().upsert(NoteEntity(isTask=true,reminderAt=now-1000))
        db.noteDao().upsert(NoteEntity(isTask=true,taskDueAt=now-1))
        db.noteDao().upsert(NoteEntity(isTask=true,taskDueAt=now))
        db.noteDao().upsert(NoteEntity(isTask=true,isCompleted=true,taskDueAt=now-1000))
        val s=AnalyticsSummary.build(db.noteDao().observeForAnalytics().first(),emptyList(),AnalyticsScope.All,
            LocalDate.of(2026,11,27),ZoneId.of("UTC"),now,request=AnalyticsListRequest(AnalyticsTaskList.OVERDUE))
        assertEquals(1,s.overdueTasks);assertEquals(1,s.taskPage!!.total)
    }
    @Test fun `fresh access rejects protected moved diary deleted purged and reused identities`() = runBlocking {
        val book=db.notebookDao().upsert(NotebookEntity(name="Work"))
        val source=NoteEntity(title="Allowed",notebookId=book,isTask=true,syncId="original")
        val id=db.noteDao().upsert(source)
        val vm=AnalyticsViewModel(app).also {store.put("vm",it)}
        val row=AnalyticsTaskRow(id,"original","Allowed",null,null)
        assertTrue(vm.taskAccessible(row))
        for (changed in listOf(source.copy(id=id,isProtected=true),source.copy(id=id,isDeleted=true),
            source.copy(id=id,isPurged=true),source.copy(id=id,syncId="replacement"))) {
            db.noteDao().update(changed);assertFalse(vm.taskAccessible(row))
        }
        db.noteDao().update(source.copy(id=id))
        val originalBook=db.notebookDao().getById(book)!!
        db.notebookDao().update(originalBook.copy(isDiary=true));assertFalse(vm.taskAccessible(row))
        db.notebookDao().update(originalBook.copy(isProtected=true));assertFalse(vm.taskAccessible(row))
        db.notebookDao().update(originalBook);assertTrue(vm.taskAccessible(row))
        assertFalse(vm.taskAccessible(row.copy(syncId="")))
    }
    @Test fun `Android application only viewmodel factory still constructs analytics`() {
        val factory=ViewModelProvider.AndroidViewModelFactory(app)
        val vm=ViewModelProvider(store,factory)[AnalyticsViewModel::class.java]
        assertTrue(vm.timeSource()>0);assertEquals(AnalyticsPeriod.WEEK,vm.period.value)
        vm.showList(AnalyticsTaskList.ACTIVE);assertEquals(AnalyticsTaskList.ACTIVE,vm.listRequest.value!!.kind)
        vm.selectScope(AnalyticsScope.Unfiled);assertNull(vm.listRequest.value)
    }
}
