package com.notessync.app.ui.analytics

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.model.AnalyticsEntry
import com.notessync.app.data.local.model.AnalyticsNotebook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class AnalyticsViewModel @JvmOverloads constructor(application: Application,
    private val clock: () -> Long = System::currentTimeMillis) : AndroidViewModel(application) {
    private val db = AppDatabase.get(application)
    private val requestedScope = MutableStateFlow<AnalyticsScope>(AnalyticsScope.All)
    private val requestedPeriod = MutableStateFlow(AnalyticsPeriod.WEEK)
    private val requestedList = MutableStateFlow<AnalyticsListRequest?>(null)
    val scope = requestedScope.asStateFlow()
    val period = requestedPeriod.asStateFlow()
    val listRequest = requestedList.asStateFlow()
    private val refreshToken = MutableStateFlow(0L)
    val notebooks: StateFlow<List<AnalyticsNotebook>?> = db.notebookDao().observeForAnalytics().distinctUntilChanged().flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0, 0), null)

    private data class Inputs(val entries: List<AnalyticsEntry>, val books: List<AnalyticsNotebook>?,
        val scope: AnalyticsScope, val period: AnalyticsPeriod, val request: AnalyticsListRequest?)
    val state: StateFlow<AnalyticsState> = combine(db.noteDao().observeForAnalytics().distinctUntilChanged().flowOn(Dispatchers.Default),
        notebooks, requestedScope, requestedPeriod, requestedList, ::Inputs)
        .combine(refreshToken) { input, _ -> input }.mapLatest { input ->
            if (input.books == null) AnalyticsState(scope = input.scope) else {
                val computed = withContext(Dispatchers.Default) {
                    val now = clock(); val zone = ZoneId.systemDefault()
                    AnalyticsSummary.build(input.entries, input.books, input.scope,
                        Instant.ofEpochMilli(now).atZone(zone).toLocalDate(), zone, now, input.period, input.request)
                }
                if (requestedScope.value == input.scope && computed.scope != input.scope) requestedScope.value = computed.scope
                if (requestedPeriod.value == input.period && requestedList.value == input.request && computed.listRequest != input.request)
                    requestedList.value = computed.listRequest
                computed
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(0, 0), AnalyticsState())

    fun selectScope(scope: AnalyticsScope) { requestedScope.value = scope; requestedList.value = null }
    fun selectPeriod(period: AnalyticsPeriod) {
        requestedPeriod.value = period
        requestedList.value = requestedList.value?.copy(page = 0)
    }
    fun showList(kind: AnalyticsTaskList) { requestedList.value = AnalyticsListRequest(kind) }
    fun closeList() { requestedList.value = null }
    fun selectPage(page: Int) { requestedList.value = requestedList.value?.copy(page = page) }
    fun refresh() { refreshToken.value++ }
    fun timeSource(): Long = clock()

    /** A scalar SQL access check: even unlocked protected/diary records never open from analytics. */
    internal suspend fun taskAccessible(row: AnalyticsTaskRow): Boolean = withContext(Dispatchers.IO) {
        if (row.syncId.isBlank()) return@withContext false
        val access = db.noteDao().getAccess(row.id) ?: return@withContext false
        access.id == row.id && access.syncId == row.syncId && !access.isDeleted && !access.isPurged &&
            !access.isProtected && !access.notebookProtected && !access.notebookDiary
    }
    private fun currentRow(row: AnalyticsTaskRow): Boolean {
        val visible = AnalyticsSummary.visible(state.value, requestedScope.value, notebooks.value, requestedPeriod.value, requestedList.value)
        if (visible.loading) return false
        return if (requestedList.value != null) visible.taskPage?.rows?.any { it.id == row.id && it.syncId == row.syncId } == true
        else visible.recentCompleted.any { it.id == row.id && it.syncId == row.syncId }
    }
    fun openTask(row: AnalyticsTaskRow, onOpen: (Long) -> Unit) {
        if (!currentRow(row)) return
        val filter = Triple(requestedScope.value, requestedPeriod.value, requestedList.value)
        viewModelScope.launch {
            if (taskAccessible(row) && filter == Triple(requestedScope.value, requestedPeriod.value, requestedList.value) && currentRow(row)) onOpen(row.id)
        }
    }
    fun openRecent(entry: AnalyticsEntry, onOpen: (Long) -> Unit) = openTask(
        AnalyticsTaskRow(entry.id, entry.syncId, entry.title, entry.taskCompletedAt ?: entry.updatedAt, entry.taskDueAt), onOpen)
}
