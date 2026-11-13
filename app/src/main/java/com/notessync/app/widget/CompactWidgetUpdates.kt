package com.notessync.app.widget

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.model.CompactWidgetEntry
import com.notessync.app.settings.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/** Event-driven only while this widget is installed. No periodic alarm, worker or service. */
object CompactWidgetUpdates {
    private var owner: Owner? = null
    fun startIfPresent(context: Context) { bind(context) }
    fun request(context: Context, onComplete: () -> Unit = {}) {
        val current = bind(context)
        if (current == null) { onComplete(); return }
        val target = current.revision.updateAndGet { it + 1 }
        // Enter try/finally before returning to a BroadcastReceiver, even if deletion cancels us.
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
        val ids = AppWidgetManager.getInstance(app).getAppWidgetIds(ComponentName(app, CompactWidgetProvider::class.java)).toSet()
        if (ids.isEmpty()) { stop(); return null }
        val old = owner
        if (old != null && old.app === app && !old.failed.get()) { old.ids.value = ids; return old }
        stop()
        return Owner(app, ids).also { owner = it; it.start() }
    }
    private data class Snapshot(val entries: List<CompactWidgetEntry>, val preferences: CompactWidgetPreferences,
        val ids: Set<Int>, val revision: Long)
    private data class Key(val entries: List<CompactWidgetEntry>, val preferences: CompactWidgetPreferences, val revision: Long)
    private class Owner(val app: Application, initial: Set<Int>) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val ids = MutableStateFlow(initial)
        val revision = MutableStateFlow(0L)
        val ack = MutableStateFlow(-1L)
        val failed = AtomicBoolean(false)
        private val render = Mutex()
        private val cache = mutableMapOf<Int, Key>()
        private var previousVisibility: List<Pair<Long, String>>? = null
        private var previousSettings: CompactWidgetPreferences? = null
        private var previousRevision = -1L
        fun start() { scope.launch {
            try {
                combine(AppDatabase.get(app).noteDao().observeCompactWidget().distinctUntilChanged(),
                    SettingsRepository.get(app).state.map(CompactWidgetPreferences::from).distinctUntilChanged(), ids, revision) { entries, prefs, widgets, stamp ->
                    Snapshot(entries, prefs, widgets, stamp)
                }.collectLatest { snapshot ->
                    val visibility = snapshot.entries.map { it.id to it.syncId }
                    val immediate = previousVisibility != visibility || previousSettings != snapshot.preferences ||
                        previousRevision != snapshot.revision || snapshot.ids.any { it !in cache }
                    if (!immediate) delay(250) // A trailing update retains the final keystroke.
                    render.withLock {
                        cache.keys.retainAll(snapshot.ids)
                        val manager = AppWidgetManager.getInstance(app)
                        val key = Key(snapshot.entries, snapshot.preferences, snapshot.revision)
                        snapshot.ids.forEach { id ->
                            currentCoroutineContext().ensureActive()
                            if (cache[id] != key) {
                                manager.updateAppWidget(id, CompactWidgetViews.build(app, id, snapshot.entries, snapshot.preferences))
                                cache[id] = key
                            }
                        }
                        previousVisibility = visibility; previousSettings = snapshot.preferences; previousRevision = snapshot.revision
                        ack.value = maxOf(ack.value, snapshot.revision)
                    }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                val preferences = CompactWidgetPreferences.from(SettingsRepository.get(app).state.value)
                val manager = AppWidgetManager.getInstance(app)
                ids.value.forEach { id ->
                    currentCoroutineContext().ensureActive()
                    runCatching { manager.updateAppWidget(id, CompactWidgetViews.build(app, id, emptyList(), preferences, failed = true)) }
                }
                failed.set(true)
                ack.value = revision.value
            }
        } }
    }
}
