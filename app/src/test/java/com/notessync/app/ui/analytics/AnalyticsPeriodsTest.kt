package com.notessync.app.ui.analytics

import com.notessync.app.data.local.model.AnalyticsEntry
import com.notessync.app.data.local.model.AnalyticsNotebook
import com.notessync.app.ui.tasks.TaskDeadlines
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class AnalyticsPeriodsTest {
    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026,11,27)
    private val now = today.atTime(12,0).atZone(zone).toInstant().toEpochMilli()
    private fun done(id:Long,at:Long)=AnalyticsEntry(id=id,title="Task $id",isTask=true,isCompleted=true,taskCompletedAt=at,syncId="s$id")
    private fun state(rows:List<AnalyticsEntry>,period:AnalyticsPeriod=AnalyticsPeriod.WEEK,request:AnalyticsListRequest?=null)=
        AnalyticsSummary.build(rows,emptyList(),AnalyticsScope.All,today,zone,now,period,request)

    @Test fun `seven day windows include their boundaries without overlap and exclude future instants today`() {
        val w=AnalyticsWindows.at(now,zone,AnalyticsPeriod.WEEK)
        val entries=listOf(done(1,w.currentStart),done(2,now),done(3,w.currentStart-1),done(4,w.previousStart),
            done(5,w.previousStart-1),done(6,now+1),done(7,now+1000),done(8,now).copy(isTask=false),done(9,now).copy(isCompleted=false))
        val s=state(entries)
        assertEquals(AnalyticsComparison(2,2),s.comparison);assertEquals(0,s.comparison.difference)
        assertEquals(2,s.completedLast7Days)
        assertEquals(today.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli(),w.currentStart)
        assertEquals(today.minusDays(13).atStartOfDay(zone).toInstant().toEpochMilli(),w.previousStart)
    }
    @Test fun `thirty day comparison handles zero baseline and completion timestamp fallback`() {
        val w=AnalyticsWindows.at(now,zone,AnalyticsPeriod.MONTH)
        val entries=listOf(done(1,w.currentStart),done(2,now).copy(taskCompletedAt=null,updatedAt=now),done(3,w.previousStart-1),done(4,now+1))
        val s=state(entries,AnalyticsPeriod.MONTH)
        assertEquals(AnalyticsComparison(2,0),s.comparison);assertEquals(2,s.comparison.difference)
        assertEquals(AnalyticsComparison(0,1),state(listOf(done(1,w.currentStart-1)),AnalyticsPeriod.MONTH).comparison)
    }
    @Test fun `local calendar windows survive spring and autumn daylight saving transitions`() {
        val berlin=ZoneId.of("Europe/Berlin")
        for ((date,hours) in listOf(LocalDate.of(2026,3,29) to 167L,LocalDate.of(2026,10,25) to 169L)) {
            val at=date.atTime(12,0).atZone(berlin).toInstant().toEpochMilli()
            val w=AnalyticsWindows.at(at,berlin,AnalyticsPeriod.WEEK)
            val end=date.plusDays(1).atStartOfDay(berlin).toInstant().toEpochMilli()
            assertEquals(hours*3_600_000,end-w.currentStart)
            val tasks=(0L..13L).map { ago -> done(ago,date.minusDays(ago).atTime(12,0).atZone(berlin).toInstant().toEpochMilli()) }
            assertEquals(AnalyticsComparison(7,7),AnalyticsPeriods.comparison(tasks,w))
        }
    }
    @Test fun `overdue means valid separate deadline strictly before now on an unfinished task`() {
        val active=AnalyticsEntry(id=1,isTask=true,taskDueAt=now-1)
        assertTrue(AnalyticsPeriods.overdue(active,now));assertFalse(AnalyticsPeriods.overdue(active.copy(taskDueAt=now),now))
        assertFalse(AnalyticsPeriods.overdue(active.copy(taskDueAt=null),now));assertFalse(AnalyticsPeriods.overdue(active.copy(isCompleted=true),now))
        assertFalse(AnalyticsPeriods.overdue(active.copy(isTask=false),now));assertFalse(AnalyticsPeriods.overdue(active.copy(taskDueAt=Long.MIN_VALUE),now))
        assertFalse(AnalyticsPeriods.overdue(active.copy(taskDueAt=Long.MAX_VALUE),now))
        val s=state(listOf(active,active.copy(id=2,taskDueAt=now),active.copy(id=3,taskDueAt=now+100)))
        assertEquals(1,s.overdueTasks);assertEquals(now,s.nearestDeadline)
        assertEquals(now+1,TaskDeadlines.nextRefresh(now,s.nearestDeadline,zone,s.capturedAt))
    }
    @Test fun `twenty row pages have deterministic ties exact count and clamp after deletion`() {
        val rows=(1L..55L).map { done(it,now) }.reversed()
        val request=AnalyticsListRequest(AnalyticsTaskList.CURRENT)
        val all=(0..2).flatMap { p -> state(rows,request=request.copy(page=p)).taskPage!!.rows }
        assertEquals((55L downTo 1L).toList(),all.map {it.id});assertEquals(55,all.map {it.id}.distinct().size)
        assertEquals(20,state(rows,request=request).taskPage!!.rows.size)
        val last=state(rows,request=request.copy(page=Int.MAX_VALUE)).taskPage!!
        assertEquals(55,last.total);assertEquals(3,last.pageCount);assertEquals(2,last.request.page);assertEquals(15,last.rows.size)
        val shrunk=state(rows.take(2),request=request.copy(page=2)).taskPage!!
        assertEquals(0,shrunk.request.page);assertEquals(2,shrunk.total)
        assertEquals(0,state(rows,request=request.copy(page=-10)).taskPage!!.request.page)
    }
    @Test fun `active and overdue lists use stable independent sort orders and empty pages remain bounded`() {
        val rows=listOf(AnalyticsEntry(id=1,isTask=true,updatedAt=5,taskDueAt=now-1),
            AnalyticsEntry(id=2,isTask=true,updatedAt=10,taskDueAt=now-2),AnalyticsEntry(id=3,isTask=true,updatedAt=10,taskDueAt=now-2))
        assertEquals(listOf(3L,2L,1L),state(rows,request=AnalyticsListRequest(AnalyticsTaskList.ACTIVE)).taskPage!!.rows.map {it.id})
        assertEquals(listOf(2L,3L,1L),state(rows,request=AnalyticsListRequest(AnalyticsTaskList.OVERDUE)).taskPage!!.rows.map {it.id})
        val empty=state(emptyList(),request=AnalyticsListRequest(AnalyticsTaskList.CURRENT,99)).taskPage!!
        assertEquals(0,empty.total);assertEquals(1,empty.pageCount);assertEquals(0,empty.request.page)
    }
    @Test fun `cached titles disappear for diary scope period and page changes`() {
        val books=listOf(AnalyticsNotebook(1,"Work"))
        val rows=listOf(done(1,now).copy(notebookId=1,title="Sensitive"))
        val request=AnalyticsListRequest(AnalyticsTaskList.CURRENT)
        val old=AnalyticsSummary.build(rows,books,AnalyticsScope.All,today,zone,now,AnalyticsPeriod.WEEK,request)
        for (hidden in listOf(AnalyticsSummary.visible(old,AnalyticsScope.All,books,AnalyticsPeriod.MONTH,request),
            AnalyticsSummary.visible(old,AnalyticsScope.All,books,AnalyticsPeriod.WEEK,request.copy(page=1)),
            AnalyticsSummary.visible(old,AnalyticsScope.All,listOf(books[0].copy(isDiary=true)),AnalyticsPeriod.WEEK,request))) {
            assertTrue(hidden.loading);assertNull(hidden.taskPage);assertTrue(hidden.recentCompleted.isEmpty());assertNull(hidden.fastestTitle)
        }
        val fresh=AnalyticsSummary.build(rows,listOf(books[0].copy(isDiary=true)),AnalyticsScope.All,today,zone,now,AnalyticsPeriod.WEEK,request)
        assertEquals(0,fresh.totalEntries);assertEquals(0,fresh.taskPage!!.total)
    }
    @Test fun `legacy positional projections retain their original meanings`() {
        val row=AnalyticsEntry(1,"t",2,true,true,3,4,5,"violet",false,false,false)
        assertNull(row.taskDueAt);assertEquals("",row.syncId);assertEquals(5L,row.taskElapsedMillis)
        assertFalse(AnalyticsNotebook(1,"Work",false,false).isDiary)
    }
    @Test fun `one shot clock boundary follows future completion without periodic refresh`() {
        val future=now+3_600_000
        val rows=listOf(done(1,future))
        val before=state(rows)
        assertEquals(0,before.comparison.current);assertEquals(future,before.nextClockBoundary)
        assertEquals(future+1,TaskDeadlines.nextRefresh(now,before.nextClockBoundary,zone,before.capturedAt))
        val after=AnalyticsSummary.build(rows,emptyList(),AnalyticsScope.All,today,zone,future+1)
        assertEquals(1,after.comparison.current);assertNull(after.nextClockBoundary)
    }

}
