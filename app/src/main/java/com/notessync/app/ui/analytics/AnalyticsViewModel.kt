package com.notessync.app.ui.analytics

import com.notessync.app.i18n.uiText

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.data.local.entity.NotebookEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId

/** Готовое состояние экрана «Аналитика» (Этап 31). */
data class AnalyticsState(
    val loading: Boolean = true,
    val totalCompleted: Int = 0,
    val activeTasks: Int = 0,
    val completedLast7Days: Int = 0,
    val completedLast30Days: Int = 0,
    val currentStreak: Int = 0,
    val bestStreak: Int = 0,
    val byDay: List<Pair<LocalDate, Int>> = emptyList(),
    val byWeek: List<Pair<LocalDate, Int>> = emptyList(),
    val byHour: IntArray = IntArray(24),
    val byColor: List<Pair<String, Int>> = emptyList(),
    val byNotebook: List<Pair<String?, Int>> = emptyList(),
    val bestDay: Pair<LocalDate, Int>? = null,
    val totalElapsedMillis: Long = 0L,
    val averageElapsedMillis: Long = 0L,
    val fastestTitle: String? = null,
    val fastestMillis: Long = 0L,
    val slowestTitle: String? = null,
    val slowestMillis: Long = 0L,
    val recentCompleted: List<NoteEntity> = emptyList()
)

class AnalyticsViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.get(application)

    val state: StateFlow<AnalyticsState> = combine(
        db.noteDao().observeTasksForAnalytics(),
        db.notebookDao().observeAll()
    ) { tasks, notebooks -> buildState(tasks, notebooks) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnalyticsState())

    private fun buildState(tasks: List<NoteEntity>, notebooks: List<NotebookEntity>): AnalyticsState {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val completed = tasks.filter { it.isCompleted }
        // У старых записей без отметки времени используем дату изменения (миграция 8->9).
        val points = completed.map { note ->
            TaskStatPoint(
                noteId = note.id,
                title = note.title,
                completedAt = note.taskCompletedAt ?: note.updatedAt,
                elapsedMillis = note.taskElapsedMillis,
                color = note.taskColor,
                notebookId = note.notebookId
            )
        }
        val notebookNames = notebooks.associate { it.id to it.name }
        val byDay = AnalyticsMath.byDay(points, 14, today, zone)
        val fastest = AnalyticsMath.fastest(points)
        val slowest = AnalyticsMath.slowest(points)
        return AnalyticsState(
            loading = false,
            totalCompleted = completed.size,
            activeTasks = tasks.count { !it.isCompleted },
            completedLast7Days = byDay.takeLast(7).sumOf { it.second },
            completedLast30Days = points.count {
                AnalyticsMath.dayOf(it.completedAt, zone) >= today.minusDays(29)
            },
            currentStreak = AnalyticsMath.currentStreak(points, today, zone),
            bestStreak = AnalyticsMath.bestStreak(points, zone),
            byDay = byDay,
            byWeek = AnalyticsMath.byWeek(points, 8, today, zone),
            byHour = AnalyticsMath.byHour(points, zone),
            byColor = AnalyticsMath.byColor(points),
            byNotebook = AnalyticsMath.byNotebook(points).map { (id, count) ->
                id?.let { notebookNames[it] } to count
            },
            bestDay = AnalyticsMath.bestDay(points, zone),
            totalElapsedMillis = AnalyticsMath.totalElapsed(points),
            averageElapsedMillis = AnalyticsMath.averageElapsed(points),
            fastestTitle = fastest?.title,
            fastestMillis = fastest?.elapsedMillis ?: 0L,
            slowestTitle = slowest?.title,
            slowestMillis = slowest?.elapsedMillis ?: 0L,
            recentCompleted = completed.sortedByDescending { it.taskCompletedAt ?: it.updatedAt }.take(20)
        )
    }
}
