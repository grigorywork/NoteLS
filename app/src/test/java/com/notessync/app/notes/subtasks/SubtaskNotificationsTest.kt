package com.notessync.app.notes.subtasks

import android.app.*
import androidx.room.withTransaction
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.*
import com.notessync.app.data.subtasks.*
import com.notessync.app.reminders.createReminderChannel
import com.notessync.app.security.ProtectionSession
import com.notessync.app.tasks.running.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SubtaskNotificationsTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private val db get()=AppDatabase.get(app)
    private val manager get()=app.getSystemService(NotificationManager::class.java)
    @Before fun before() {
        AppDatabase.closeInstance();app.deleteDatabase("notessync.db");manager.cancelAll();app.createReminderChannel()
        val alarms=app.getSystemService(AlarmManager::class.java)
        shadowOf(alarms).scheduledAlarms.toList().forEach { it.operation?.let { pi -> alarms.cancel(pi) } }
    }
    @After fun after() { AppDatabase.closeInstance();manager.cancelAll();ProtectionSession.lockAll() }
    private suspend fun parent(hidden:Boolean=false)=NoteEntity(content="Original",tablesData="opaque",isProtected=hidden,createdAt=7)
        .let { it.copy(id=db.noteDao().upsert(it)) }
    @Test fun `scalar child query masks unlocked protected titles excludes closed parents and bounds live rows`()=runBlocking {
        val now=System.currentTimeMillis();val public=parent();val hidden=parent(true);ProtectionSession.unlockTask(hidden.syncId)
        db.withTransaction {
            replaceSubtasks(db,public.id,listOf(Subtask("a","Visible",startedAt=now)))
            replaceSubtasks(db,hidden.id,listOf(Subtask("a","Do not load private child title",startedAt=now)))
        }
        val rows=db.subtaskDao().getRunning(now);assertEquals(2,rows.size)
        assertEquals("",rows.single { it.id==hidden.id }.title);assertTrue(rows.single { it.id==hidden.id }.hidden)
        val privateRow=rows.single { it.id==hidden.id };assertTrue(RunningTaskNotifications.build(app,privateRow,RunningClock.STOPWATCH,false,true,now).actions.isNullOrEmpty())
        db.noteDao().update(public.copy(isTask=true,isCompleted=true));assertEquals(listOf(hidden.id),db.subtaskDao().getRunning(now).map { it.id })
        db.withTransaction { replaceSubtasks(db,hidden.id,(0..29).map { Subtask("id$it","Private",startedAt=now) }) }
        assertEquals(20,db.subtaskDao().getRunning(now).size)
    }
    @Test fun `native actions contain parent child and expected clock identity and never alter sibling or parent document`()=runBlocking {
        val now=System.currentTimeMillis();val note=parent();val first=Subtask("a","First",startedAt=now-500,countdownMillis=1000,countdownEndAt=now+1000)
        db.withTransaction { replaceSubtasks(db,note.id,listOf(first,Subtask("b","Second",startedAt=now-100))) }
        val row=db.subtaskDao().getRunning(now).first { it.subtaskId=="a" }
        val notification=RunningTaskNotifications.build(app,row,RunningClock.STOPWATCH,false,true,now)
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        val command=RunningTaskReceiver.read(shadowOf(notification.actions[0].actionIntent).savedIntent)!!
        assertEquals("a",command.subtaskId);assertEquals(note.syncId,command.syncId)
        assertFalse(RunningTaskActions.apply(app,command.copy(syncId="reused"),now))
        assertFalse(RunningTaskActions.apply(app,command.copy(expectedAt=now),now))
        assertTrue(RunningTaskActions.apply(app,command,now))
        val children=db.subtaskDao().get(note.id);assertNull(children.first { it.id=="a" }.startedAt)
        assertEquals(first.countdownEndAt,children.first { it.id=="a" }.countdownEndAt);assertNotNull(children.first { it.id=="b" }.startedAt)
        val parent=db.noteDao().getById(note.id)!!;assertEquals(note.content,parent.content);assertEquals(note.tablesData,parent.tablesData);assertEquals(note.createdAt,parent.createdAt)
        assertFalse(parent.isCompleted)
    }
    @Test fun `all child countdowns share one nearest one-shot alarm and delivery preserves stopwatch and the next deadline`()=runBlocking {
        val now=System.currentTimeMillis();val note=parent();val end=now+1000
        db.withTransaction { replaceSubtasks(db,note.id,listOf(Subtask("a","First",startedAt=now-100,countdownMillis=1000,countdownEndAt=end),Subtask("b","Second",countdownMillis=5000,countdownEndAt=now+5000))) }
        repeat(3) { SubtaskAlarms.reconcile(app,db) }
        val alarms=shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms
            .filter { it.operation?.let { pi -> shadowOf(pi).savedIntent.action==SubtaskAlarms.ACTION }==true }
        assertEquals(1,alarms.size);assertEquals(end,alarms.single().triggerAtTime)
        SubtaskAlarms.deliver(app,db,end)
        val children=db.subtaskDao().get(note.id);val first=children.first { it.id=="a" }
        assertEquals(end,first.countdownFinishedAt);assertNull(first.countdownEndAt);assertNotNull(first.startedAt);assertFalse(first.completed)
        assertEquals(now+5000,db.subtaskDao().nextAlarm());assertTrue(manager.activeNotifications.any { it.notification.extras.getString(Notification.EXTRA_TITLE)=="First" })
        assertEquals("Original",db.noteDao().getById(note.id)!!.content)
    }
    @Test fun `delivered child alarm survives watch pause and text edit but explicit timer removal and stale identity clear it`()=runBlocking {
        val now=System.currentTimeMillis();val note=parent();val end=now+100
        db.withTransaction { replaceSubtasks(db,note.id,listOf(Subtask("a","Child",startedAt=now-100,countdownMillis=100,countdownEndAt=end))) }
        SubtaskAlarms.deliver(app,db,end)
        assertTrue(manager.activeNotifications.any { it.tag.startsWith("notels:subtask-alarm:") })
        assertTrue(RunningTaskActions.apply(app,RunningControl(note.id,note.syncId,RunningClock.STOPWATCH,now-100,RunningAction.PAUSE,"a"),end))
        db.withTransaction { replaceSubtasks(db,note.id,db.subtaskDao().get(note.id).map { it.item().copy(text="Edited") }) }
        SubtaskAlarms.reconcile(app,db);assertTrue(manager.activeNotifications.any { it.tag.startsWith("notels:subtask-alarm:") })
        db.withTransaction { replaceSubtasks(db,note.id,db.subtaskDao().get(note.id).map { it.item().copy(countdownFinishedAt=null) }) }
        SubtaskAlarms.reconcile(app,db);assertFalse(manager.activeNotifications.any { it.tag.startsWith("notels:subtask-alarm:") })
        assertEquals("Edited",db.subtaskDao().get(note.id).single().text)
    }
    @Test fun `protected timer delivery stays generic and a removed child or completed parent cannot accept stale commands`()=runBlocking {
        val now=System.currentTimeMillis();val privateNote=parent(true)
        db.withTransaction { replaceSubtasks(db,privateNote.id,listOf(Subtask("a","Never expose",countdownMillis=100,countdownEndAt=now+100))) }
        SubtaskAlarms.deliver(app,db,now+100)
        assertTrue(manager.activeNotifications.isNotEmpty());assertTrue(manager.activeNotifications.none { it.notification.extras.toString().contains("Never expose") })
        val public=parent();db.withTransaction { replaceSubtasks(db,public.id,listOf(Subtask("a",startedAt=now))) }
        val command=RunningControl(public.id,public.syncId,RunningClock.STOPWATCH,now,RunningAction.COMPLETE,"a")
        db.noteDao().update(public.copy(isTask=true,isCompleted=true));assertFalse(RunningTaskActions.apply(app,command,now+100))
        db.noteDao().update(public.copy(isTask=true,isCompleted=false));assertFalse(RunningTaskActions.apply(app,command,now+100))
    }
}
