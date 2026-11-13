package com.notessync.app.widget

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.room.InvalidationTracker
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.settings.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicBoolean

/** Installed widgets only; bounded, event-driven DB snapshots and no background clock. */
object WeeklyWidgetUpdates {
    private var owner: Owner? = null
    private val render = Mutex()
    fun startIfPresent(context: Context) { request(context) }
    fun request(context: Context, onComplete: () -> Unit = {}) {
        val current = bind(context)
        if (current == null) { onComplete(); return }
        val target = current.revision.updateAndGet { it + 1 }
        current.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { withTimeout(8_000) { current.ack.first { it >= target } } }
            finally { runCatching { onComplete() } }
        }
    }
    @Synchronized fun stop(): Job? {
        val previous = owner ?: return null
        owner = null
        val job = previous.scope.coroutineContext[Job]
        previous.scope.cancel()
        return job
    }
    @Synchronized private fun bind(context: Context): Owner? {
        val app = context.applicationContext as Application
        val ids = AppWidgetManager.getInstance(app).getAppWidgetIds(ComponentName(app, WeeklyWidgetProvider::class.java)).toSet()
        if (ids.isEmpty()) { stop(); return null }
        owner?.takeIf { it.app === app && !it.failed.get() }?.let { it.ids.value = ids; return it }
        stop()
        return Owner(app, ids).also { owner = it; it.start() }
    }
    private data class Request(val preferences: CompactWidgetPreferences, val ids: Set<Int>, val revision: Long)
    private data class Frame(val selection: WeekSelection, val days: Map<Long, WidgetDaySnapshot>, val today: java.time.LocalDate)
    private data class Snapshot(val request: Request, val frames: Map<Int, Frame>)
    private data class Key(val frame: Frame, val preferences: CompactWidgetPreferences, val revision: Long)
    private class Owner(val app: Application, initial: Set<Int>) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val ids = MutableStateFlow(initial)
        val revision = MutableStateFlow(0L)
        val ack = MutableStateFlow(-1L)
        val failed = AtomicBoolean(false)
        private val cache = mutableMapOf<Int, Key>()
        private fun changes(db: AppDatabase) = callbackFlow {
            val observer = object : InvalidationTracker.Observer("notes", "notebooks") {
                override fun onInvalidated(tables: Set<String>) { trySend(Unit) }
            }
            db.invalidationTracker.addObserver(observer)
            trySend(Unit)
            awaitClose { db.invalidationTracker.removeObserver(observer) }
        }.conflate()
        @OptIn(ExperimentalCoroutinesApi::class)
        fun start() { scope.launch {
            try {
                val db = AppDatabase.get(app)
                combine(changes(db), SettingsRepository.get(app).state.map(CompactWidgetPreferences::from).distinctUntilChanged(), ids, revision) {
                    _, preferences, widgets, stamp -> Request(preferences, widgets, stamp)
                }.mapLatest { request ->
                    val today = java.time.LocalDate.now()
                    val selections = request.ids.associateWith { WeeklyWidgetProvider.state(app, it, today) }
                    val weeks = selections.values.map { it.first }.distinct().associateWith { first ->
                        loadWidgetDays(db, first.toEpochDay(), first.plusDays(6).toEpochDay())
                    }
                    Snapshot(request, selections.mapValues { (_, selection) -> Frame(selection, weeks.getValue(selection.first), today) })
                }.collectLatest { snapshot ->
                    val request = snapshot.request
                    val immediate = snapshot.frames.any { (id, frame) ->
                        val previous = cache[id]
                        previous == null || previous.preferences != request.preferences || previous.revision != request.revision ||
                            previous.frame.selection != frame.selection || previous.frame.today != frame.today ||
                            previous.frame.days.mapValues { (_, day) -> day.entries.map { it.id } to day.total } !=
                            frame.days.mapValues { (_, day) -> day.entries.map { it.id } to day.total }
                    }
                    if (!immediate) delay(250) // The final title edit is always rendered.
                    render.withLock {
                        currentCoroutineContext().ensureActive()
                        cache.keys.retainAll(request.ids)
                        val manager = AppWidgetManager.getInstance(app)
                        snapshot.frames.forEach { (id, frame) ->
                            currentCoroutineContext().ensureActive()
                            val key = Key(frame, request.preferences, request.revision)
                            if (cache[id] != key) {
                                manager.updateAppWidget(id, WeeklyWidgetViews.build(app, id, frame.selection, frame.days, request.preferences, frame.today))
                                cache[id] = key
                            }
                        }
                        ack.value = maxOf(ack.value, request.revision)
                    }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                render.withLock {
                    currentCoroutineContext().ensureActive()
                    val preferences = CompactWidgetPreferences.from(SettingsRepository.get(app).state.value)
                    val manager = AppWidgetManager.getInstance(app)
                    ids.value.forEach { id ->
                        currentCoroutineContext().ensureActive()
                        runCatching { manager.updateAppWidget(id, WeeklyWidgetViews.build(app, id, WeeklyWidgetProvider.state(app, id), emptyMap(), preferences, failed = true)) }
                    }
                    failed.set(true)
                    ack.value = revision.value
                }
            }
        } }
    }
}
