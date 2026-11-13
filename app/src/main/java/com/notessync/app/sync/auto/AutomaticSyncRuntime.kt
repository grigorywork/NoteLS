package com.notessync.app.sync.auto

import android.app.Application
import android.content.Context
import androidx.room.InvalidationTracker
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.settings.SettingsRepository
import com.notessync.app.sync.android.RoomSyncDataSource
import com.notessync.app.sync.core.SyncWorkGate
import com.notessync.app.sync.trust.AndroidTrustedPeers
import com.notessync.app.sync.trust.TrustedPeers
import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*

/** Application foreground, one Wi-Fi network generation, no Service/Worker or idle polling. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class AutomaticSyncRuntime internal constructor(
    private val app: Application,
    val peers: TrustedPeers = AndroidTrustedPeers(app),
    private val networks: () -> Flow<AutoNetwork?> = { wifiLinks(app).map { link -> link?.let { AndroidAutoNetwork(app, it) } } }
) : Closeable {
    private val settings = SettingsRepository.get(app)
    private val foreground = MutableStateFlow(false)
    private val retryEpoch = MutableStateFlow(0)
    private var loading: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val current = AtomicReference<AutomaticEngine?>()
    private val mutable = MutableStateFlow(AutoStatus())
    val state = mutable.asStateFlow()
    private data class Eligibility(val foreground: Boolean, val enabled: Boolean, val ready: Boolean, val available: Boolean,
        val identities: List<Pair<String, String>>, val paused: Boolean, val retry: Int)

    init {
        scope.launch {
            combine(foreground, settings.state.map { it.autoSyncEnabled }.distinctUntilChanged(),
                peers.state.map { Triple(it.ready, it.available, it.peers.map { p -> p.id to p.revision }.sortedBy { p -> p.first }) }.distinctUntilChanged(),
                SyncWorkGate.pauses.map { it != 0 }.distinctUntilChanged(), retryEpoch) { visible, enabled, trusted, paused, retry ->
                Eligibility(visible, enabled, trusted.first, trusted.second, trusted.third, paused, retry)
            }.distinctUntilChanged().collectLatest { eligibility ->
                stopNetwork()
                when {
                    !eligibility.enabled -> mutable.value = AutoStatus(AutoPhase.DISABLED)
                    !eligibility.foreground || eligibility.paused -> mutable.value = AutoStatus(AutoPhase.PAUSED)
                    !eligibility.ready -> mutable.value = AutoStatus(AutoPhase.LOADING)
                    !eligibility.available -> mutable.value = AutoStatus(AutoPhase.UNAVAILABLE)
                    eligibility.identities.isEmpty() -> mutable.value = AutoStatus(AutoPhase.PAIR_FIRST)
                    else -> try {
                        networks().collectLatest network@{ network ->
                            stopNetwork()
                            if (network == null) { mutable.value = AutoStatus(AutoPhase.NO_WIFI); return@network }
                            val database = withContext(Dispatchers.IO) { AppDatabase.get(app) }
                            val identity = withContext(Dispatchers.IO) { SyncIdentity.id(app) }
                            lateinit var engine: AutomaticEngine
                            engine = AutomaticEngine(CoroutineScope(currentCoroutineContext()), network, peers,
                                RoomSyncDataSource(app, database), identity, android.os.Build.MODEL,
                                changes(database), report = { if (current.get() === engine) mutable.value = it })
                            current.set(engine)
                            try { withContext(Dispatchers.IO) { engine.start() }; awaitCancellation() }
                            catch (cancel: CancellationException) { throw cancel }
                            catch (_: Exception) { mutable.value = AutoStatus(AutoPhase.ERROR) }
                            finally { current.compareAndSet(engine, null); engine.close() }
                        }
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { mutable.value = AutoStatus(AutoPhase.ERROR) }
                }
            }
        }
    }
    fun foreground(value: Boolean) {
        foreground.value = value
        if (!value) stopNetwork()
        else if (!peers.state.value.ready && loading?.isActive != true) loading = scope.launch { peers.load() }
    }
    fun pauseManual(): Closeable = SyncWorkGate.pauseAutomatic().also { stopNetwork() }
    fun syncNow() {
        val engine = current.get()
        if (engine != null) engine.requestAll() else scope.launch { peers.load(); retryEpoch.value++ }
    }
    suspend fun remember(id: String, name: String, secret: String, host: String, started: Long) {
        peers.pair(id, name, secret, host, started)
    }
    suspend fun forget(id: String) {
        val pause = pauseManual()
        try { peers.forget(id) } finally { pause.close() }
    }
    suspend fun resetConnections() {
        val pause = pauseManual()
        try {
            settings.setAutoSyncEnabled(false)
            peers.reset(); withContext(Dispatchers.IO) { SyncIdentity.reset(app) }
        } finally { pause.close() }
    }
    private fun changes(database: AppDatabase) = callbackFlow<Unit> {
        val observer = object : InvalidationTracker.Observer("notes", "notebooks", "attachments", "tags", "note_tag_cross_ref") {
            override fun onInvalidated(tables: Set<String>) { trySend(Unit) }
        }
        database.invalidationTracker.addObserver(observer)
        trySend(Unit) // Include changes made while the first connection/observer was starting.
        awaitClose { database.invalidationTracker.removeObserver(observer) }
    }.conflate()
    private fun stopNetwork() { current.getAndSet(null)?.close() }
    override fun close() { foreground.value = false; stopNetwork(); scope.cancel() }
    companion object {
        @Volatile private var instance: AutomaticSyncRuntime? = null
        fun get(context: Context): AutomaticSyncRuntime = synchronized(this) {
            val app = context.applicationContext as Application
            instance?.takeIf { it.app === app } ?: AutomaticSyncRuntime(app).also { instance?.close(); instance = it }
        }
        fun existing(context: Context): AutomaticSyncRuntime? = instance?.takeIf { it.app === context.applicationContext }
    }
}
