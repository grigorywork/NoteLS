package com.notessync.app.ui.calendar

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.ui.tasks.TaskCompletion
import com.notessync.app.widget.CalendarWidgetProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

data class CalendarState(
    val month: YearMonth,
    val selectedDate: LocalDate,
    val cells: List<CalendarCell>,
    val notesByDay: Map<Long, List<NoteEntity>> = emptyMap(),
    val selectedNotes: List<NoteEntity> = emptyList()
)
private data class CalendarMonthResult(val month: YearMonth, val notes: List<NoteEntity>)

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.get(application)
    private val noteDao = db.noteDao()
    private val actions = com.notessync.app.notes.actions.NoteActions(application, db)
    private val initialMonth = YearMonth.now()
    private val initialDate = LocalDate.now()
    private val visibleMonth = MutableStateFlow(initialMonth)
    private val selectedDate = MutableStateFlow(initialDate)

    private val monthNotes: StateFlow<CalendarMonthResult?> = visibleMonth.flatMapLatest { month ->
        val cells = CalendarMath.grid(month)
        noteDao.observeForDayRange(
            cells.first().date.toEpochDay(),
            cells.last().date.toEpochDay()
        ).map { CalendarMonthResult(month, it) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val state: StateFlow<CalendarState> = combine(
        visibleMonth,
        selectedDate,
        monthNotes
    ) { month, selected, notes ->
        val grouped = notes?.takeIf { it.month == month }?.notes.orEmpty().groupBy { it.dayKey!! }
        CalendarState(
            month = month,
            selectedDate = selected,
            cells = CalendarMath.grid(month),
            notesByDay = grouped,
            selectedNotes = grouped[selected.toEpochDay()].orEmpty()
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        CalendarState(initialMonth, initialDate, CalendarMath.grid(initialMonth))
    )

    fun previousMonth() = showMonth(visibleMonth.value.minusMonths(1))
    fun nextMonth() = showMonth(visibleMonth.value.plusMonths(1))

    fun showToday() {
        val today = LocalDate.now()
        visibleMonth.value = YearMonth.from(today)
        selectedDate.value = today
    }

    fun selectDate(date: LocalDate) {
        selectedDate.value = date
    }

    fun selectAgendaDate(date: LocalDate) {
        visibleMonth.value = YearMonth.from(date)
        selectedDate.value = date
    }

    private fun showMonth(month: YearMonth) {
        visibleMonth.value = month
        selectedDate.value = CalendarMath.initialSelection(month, LocalDate.now())
    }

    fun toggleCompleted(note: NoteEntity) {
        if (!note.isTask) return
        viewModelScope.launch {
            val changed = db.withTransaction {
                val fresh = noteDao.getById(note.id) ?: return@withTransaction false
                if (fresh.syncId != note.syncId || !fresh.isTask || fresh.isDeleted || fresh.isPurged ||
                    fresh.isCompleted != note.isCompleted || !actions.canAccess(fresh)) return@withTransaction false
                if (fresh.reminderAt != null || fresh.taskCountdownEndAt != null)
                    com.notessync.app.reminders.AlarmReconciler.requireRecovery(getApplication())
                noteDao.update(TaskCompletion.toggle(fresh))
                true
            }
            if (changed) {
                runCatching { com.notessync.app.reminders.AlarmReconciler.refreshNotes(getApplication(), db, setOf(note.id)) }
                CalendarWidgetProvider.refreshAll(getApplication())
            }
        }
    }
}
