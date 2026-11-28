package com.notessync.app.tasks.running

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.room.withTransaction
import com.notessync.app.MainActivity
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.i18n.UiLanguage
import com.notessync.app.i18n.uiText
import com.notessync.app.settings.SettingsRepository
import com.notessync.app.ui.tasks.TaskDurationStyle
import com.notessync.app.widget.WidgetNoteLinks
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** SystemUI owns the moving counters. No service, worker, repeating alarm, wakelock or per-second app loop. */
object RunningTaskNotifications {
    internal const val CHANNEL = "running_tasks"
    internal const val TAG_PREFIX = "notels:running:"
    private var owner: Owner? = null
    private val rendering = Mutex()
    private val generation = java.util.concurrent.atomic.AtomicLong()
    private var renderedGeneration = -1L
    private val cache = mutableMapOf<String, Key>()
    private data class Preferences(val enabled: Boolean, val english: Boolean, val units: Boolean)
    private data class Key(val row: RunningTaskEntry, val clock: RunningClock, val preferences: Preferences)
    private fun preferences(context: Context) = SettingsRepository.get(context).state.value.let {
        Preferences(it.runningTaskNotifications, it.uiLanguage == UiLanguage.ENGLISH, it.automaticDurationUnits)
    }
    @Synchronized fun start(context: Context) {
        val app = context.applicationContext
        if (owner?.app === app && owner?.job?.isActive == true) return
        stop()
        owner = Owner(app).also { it.start() }
    }
    @Synchronized fun stop() { owner?.scope?.cancel(); owner = null; generation.incrementAndGet() }
    fun request(context: Context) { start(context); synchronized(this) { owner?.let { it.revision.value++ } } }

    internal suspend fun rebase(context: Context) {
        rendering.withLock { cache.clear() }
        refreshNow(context)
        request(context)
    }

    internal fun canNotify(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        return (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }
    private fun channel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, uiText("Идущие задачи"), NotificationManager.IMPORTANCE_LOW).apply {
                description = uiText("Таймер и секундомер без постоянной фоновой работы приложения")
                setSound(null, null); enableVibration(false); setShowBadge(false)
            })
    }
    private class Owner(val app: Context) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val revision = MutableStateFlow(0L)
        var job: Job? = null
        @OptIn(ExperimentalCoroutinesApi::class)
        fun start() { job = scope.launch {
            try {
                combine(SettingsRepository.get(app).state.map { Preferences(it.runningTaskNotifications,
                    it.uiLanguage == UiLanguage.ENGLISH, it.automaticDurationUnits) }.distinctUntilChanged(), revision) { prefs, stamp -> prefs to stamp }
                    .flatMapLatest { (prefs, _) ->
                        if (!prefs.enabled) flowOf(emptyList())
                        else AppDatabase.get(app).noteDao().observeRunningTasks(System.currentTimeMillis()).distinctUntilChanged()
                    }.collectLatest {
                        val next = withTimeout(8_000) { refreshNow(app) }
                        // A single nearest-deadline boundary, never a periodic ticker. SystemUI expires the PI independently.
                        if (next != null) {
                            delay(RunningClockTime.remaining(next, System.currentTimeMillis()).coerceAtLeast(1))
                            revision.value++
                        }
                    }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { rendering.withLock { clear(app) } /* Foreground request rebinds a failed owner. */ }
        } }
    }
    /** Bounded metadata transaction also closes stale Flow/restore/protection races before publishing. */
    @SuppressLint("MissingPermission") // Fresh app/channel permission check; caller handles revocation races.
    internal suspend fun refreshNow(context: Context): Long? = rendering.withLock {
        val currentGeneration = generation.get()
        if (renderedGeneration != currentGeneration) { cache.clear(); renderedGeneration = currentGeneration }
        currentCoroutineContext().ensureActive()
        channel(context)
        val prefs = preferences(context)
        if (!prefs.enabled || !canNotify(context)) { clear(context); return@withLock null }
        val manager = context.getSystemService(NotificationManager::class.java)
        val db = AppDatabase.get(context)
        db.withTransaction {
            val now = System.currentTimeMillis()
            val rows = db.noteDao().getRunningTasks(now)
            val active = manager.activeNotifications.mapNotNull { it.tag?.takeIf { tag -> tag.startsWith(TAG_PREFIX) } }.toSet()
            val wanted = mutableSetOf<String>()
            var next: Long? = null
            rows.forEach { row ->
                listOf(RunningClock.STOPWATCH, RunningClock.COUNTDOWN).forEach { clock ->
                    val at = if (clock == RunningClock.STOPWATCH) row.taskTimerStartedAt else row.taskCountdownEndAt
                    if (at != null && (clock == RunningClock.STOPWATCH || at > now)) {
                        val tag = "$TAG_PREFIX${row.id}:${clock.name}"
                        wanted += tag
                        val key = Key(row, clock, prefs)
                        currentCoroutineContext().ensureActive()
                        if (cache[tag] != key || tag !in active) {
                            manager.notify(tag, 0, build(context, row, clock, prefs.english, prefs.units, now))
                            cache[tag] = key
                        }
                        if (clock == RunningClock.COUNTDOWN) next = next?.let { minOf(it, at) } ?: at
                    }
                }
            }
            (active - wanted).forEach { manager.cancel(it, 0) }
            cache.keys.retainAll(wanted)
            next
        }
    }
    private fun clear(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.forEach { if (it.tag?.startsWith(TAG_PREFIX) == true) manager.cancel(it.tag, it.id) }
        cache.clear()
    }
    internal fun build(context: Context, row: RunningTaskEntry, clock: RunningClock, english: Boolean,
        units: Boolean, now: Long): Notification {
        channel(context)
        val countdown = clock == RunningClock.COUNTDOWN
        val label = uiText(if (countdown) "Таймер" else "Секундомер")
        val title = if (row.hidden) uiText("Защищённая запись") else row.title.ifBlank { uiText("Задача") }
        val amount = if (countdown) RunningClockTime.remaining(requireNotNull(row.taskCountdownEndAt), now)
            else RunningClockTime.elapsed(row.taskElapsedMillis, row.taskTimerStartedAt, now)
        val native = amount <= RunningClockTime.MAX_NATIVE_MILLIS && (countdown || (row.taskTimerStartedAt ?: Long.MAX_VALUE) <= now)
        val whenAt = if (countdown) requireNotNull(row.taskCountdownEndAt) else now - amount.coerceAtMost(RunningClockTime.MAX_NATIVE_MILLIS)
        val open = if (row.hidden) Intent(context, MainActivity::class.java)
            else WidgetNoteLinks.intent(context, -90, row.id, row.syncId)
        val content = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val public = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(uiText("Идущие задачи")).setContentText(label).build()
        val builder = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title).setContentText(if (native) label else uiText("Время при обновлении: %1\$s", TaskDurationStyle(units, english).format(amount)))
            .setContentIntent(content).setOnlyAlertOnce(true).setSilent(true).setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH).setWhen(whenAt).setShowWhen(native)
            .setUsesChronometer(native).setChronometerCountDown(countdown)
        if (countdown && amount <= RunningClockTime.MAX_NATIVE_MILLIS) builder.setTimeoutAfter(amount.coerceAtLeast(1))
        if (!row.hidden && (if (countdown) row.taskCountdownEndAt else row.taskTimerStartedAt)?.let { it > 0 } == true) {
            val at = requireNotNull(if (countdown) row.taskCountdownEndAt else row.taskTimerStartedAt)
            listOf(RunningAction.PAUSE, RunningAction.COMPLETE).forEach { action ->
                val control = RunningControl(row.id, row.syncId, clock, at, action)
                val pi = PendingIntent.getBroadcast(context, 0, RunningTaskReceiver.intent(context, control), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                builder.addAction(NotificationCompat.Action.Builder(0, uiText(if (action == RunningAction.PAUSE) "Пауза" else "Выполнить"), pi)
                    .setAuthenticationRequired(true).build())
            }
        }
        return builder.build()
    }
}
