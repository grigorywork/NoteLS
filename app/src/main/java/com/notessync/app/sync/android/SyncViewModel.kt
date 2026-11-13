package com.notessync.app.sync.android

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.notessync.app.i18n.uiText
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.sync.core.*
import com.notessync.app.sync.crypto.PairingHandshake
import com.notessync.app.sync.crypto.PairingSecret
import com.notessync.app.sync.crypto.SessionCrypto
import com.notessync.app.sync.protocol.FrameTransport
import com.notessync.app.sync.qr.SyncQr
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal interface SyncConnections {
    fun localAddress(): String
    fun listen(address: String, port: Int): ServerSocket
    fun socket(): Socket = Socket()
    fun now(): Long = SystemClock.elapsedRealtime()
}

private class LanConnections : SyncConnections {
    override fun localAddress(): String {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return ""
        for (iface in interfaces) {
            if (!iface.isUp || iface.isLoopback) continue
            for (address in iface.inetAddresses) {
                if (address is Inet4Address && SyncQr.localAddress(address.hostAddress.orEmpty())) return address.hostAddress.orEmpty()
            }
        }
        return ""
    }
    override fun listen(address: String, port: Int): ServerSocket {
        val server = ServerSocket()
        try { server.bind(InetSocketAddress(address, port)); return server }
        catch (error: Exception) { server.close(); throw error }
    }
}

enum class SyncMode { IDLE, WAITING, CLIENT_FORM, SYNCING, DONE, ERROR }

data class SyncUiState(
    val mode: SyncMode = SyncMode.IDLE,
    val localIp: String = "",
    val sessionCode: String = "",
    val qrPayload: String = "",
    val peerName: String = "",
    val progress: SyncProgress = SyncProgress(SyncPhase.HANDSHAKE),
    val result: SyncResult? = null,
    val error: String? = null,
    internal val generation: Long = 0
)

class SyncViewModel internal constructor(
    application: Application,
    private val dataSource: SyncDataSource,
    private val connections: SyncConnections
) : AndroidViewModel(application), DefaultLifecycleObserver {
    constructor(application: Application) : this(application,
        RoomSyncDataSource(application, AppDatabase.get(application)), LanConnections())

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _uiState = MutableStateFlow(SyncUiState())
    val uiState: StateFlow<SyncUiState> = _uiState.asStateFlow()
    private val generations = AtomicLong()
    private val resources = AtomicReference<SyncResources?>()
    private val sessionMutex = Mutex()
    private var sessionJob: Job? = null

    val deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, null)
            ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_DEVICE_ID, it).apply() }
    val lastSyncAt: Long get() = prefs.getLong(KEY_LAST_SYNC, 0L)

    fun startWaiting() {
        cancel()
        val ip = connections.localAddress()
        if (!SyncQr.localAddress(ip)) { showInputError(uiText("Подключитесь к локальной сети Wi-Fi")); return }
        val code = PairingSecret.generate()
        val token = _uiState.value.generation
        val expires = connections.now() + SyncQr.TTL_MS
        update(token) { it.copy(mode = SyncMode.WAITING, sessionCode = code, localIp = ip,
            qrPayload = SyncQr.encode(ip, SYNC_PORT, code)) }
        launchSession(token, invitation = true) { owned ->
            val server = owned.own(connections.listen(ip, SYNC_PORT))
            var attempts = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                require(connections.now() < expires) { "Invitation expired" }
                val socket = owned.own(server.accept())
                val crypto = try {
                    socket.soTimeout = HANDSHAKE_TIMEOUT_MS
                    PairingHandshake.establish(socket.getInputStream(), socket.getOutputStream(), code, client = false)
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) {
                    owned.release(socket)
                    currentCoroutineContext().ensureActive()
                    require(++attempts < MAX_FAILED_ATTEMPTS) { "Too many invalid connections" }
                    continue
                }
                currentCoroutineContext().ensureActive()
                require(connections.now() < expires) { "Invitation expired" }
                owned.release(server)
                runSession(socket, crypto, token, client = false)
                break
            }
        }
    }

    fun connect(ip: String, code: String) {
        cancel()
        val host = ip.trim()
        val secret = code.trim()
        if (!SyncQr.localAddress(host) || !PairingSecret.valid(secret)) {
            showInputError(uiText("Укажите локальный IPv4-адрес и ключ из 64 символов")); return
        }
        val token = _uiState.value.generation
        update(token) { it.copy(mode = SyncMode.SYNCING) }
        launchSession(token, invitation = false) { owned ->
            val socket = owned.own(connections.socket())
            socket.connect(InetSocketAddress(host, SYNC_PORT), CONNECT_TIMEOUT_MS)
            currentCoroutineContext().ensureActive()
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            val crypto = PairingHandshake.establish(socket.getInputStream(), socket.getOutputStream(), secret, client = true)
            runSession(socket, crypto, token, client = true)
        }
    }

    private fun launchSession(token: Long, invitation: Boolean, block: suspend (SyncResources) -> Unit) {
        val owned = SyncResources()
        resources.set(owned)
        val worker = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                // A cancelled predecessor can finish its existing per-note transaction before the next session starts.
                sessionMutex.withLock { currentCoroutineContext().ensureActive(); block(owned) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                if (isActive) update(token) { it.copy(mode = SyncMode.ERROR, sessionCode = "", qrPayload = "",
                    error = uiText("Не удалось завершить обмен. Проверьте Wi-Fi и ключ; обновите NoteLS на обоих устройствах.")) }
            } finally { owned.close(); resources.compareAndSet(owned, null) }
        }
        sessionJob = worker
        val deadline = viewModelScope.launch {
            delay(MAX_SESSION_MS)
            if (_uiState.value.generation == token) {
                worker.cancel(); owned.close()
                update(token) { it.copy(mode = SyncMode.ERROR, sessionCode = "", qrPayload = "", error = uiText("Время обмена истекло")) }
            }
        }
        val expiry = if (invitation) viewModelScope.launch {
            delay(SyncQr.TTL_MS)
            val current = _uiState.value
            if (current.generation == token && current.mode == SyncMode.WAITING) {
                worker.cancel(); owned.close()
                update(token) { it.copy(mode = SyncMode.ERROR, sessionCode = "", qrPayload = "", error = uiText("Код просрочен — сгенерируйте новый")) }
            }
        } else null
        worker.invokeOnCompletion { deadline.cancel(); expiry?.cancel() }
        worker.start()
    }

    private suspend fun runSession(socket: Socket, crypto: SessionCrypto, token: Long, client: Boolean) {
        currentCoroutineContext().ensureActive()
        update(token) { it.copy(mode = SyncMode.SYNCING, sessionCode = "", qrPayload = "") }
        socket.soTimeout = IO_TIMEOUT_MS
        socket.use {
            val transport = FrameTransport(socket.getInputStream(), socket.getOutputStream(), crypto)
            val sessionContext = currentCoroutineContext()
            val sessionRef = AtomicReference<SyncSession>()
            val session = SyncSession(transport, dataSource, deviceId, android.os.Build.MODEL.take(128), { lastSyncAt },
                onProgress = { progress ->
                    sessionContext.ensureActive()
                    val peer = sessionRef.get()?.peerName.orEmpty()
                    update(token) { s -> s.copy(progress = progress, peerName = peer.ifBlank { s.peerName }) }
                })
            sessionRef.set(session)
            val result = if (client) session.runAsClient() else session.runAsServer()
            currentCoroutineContext().ensureActive()
            prefs.edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).apply()
            update(token) { it.copy(mode = SyncMode.DONE, result = result, peerName = session.peerName) }
        }
    }

    private fun update(token: Long, transform: (SyncUiState) -> SyncUiState) {
        _uiState.update { if (it.generation == token) transform(it) else it }
    }
    private fun showInputError(message: String) {
        _uiState.update { it.copy(mode = SyncMode.ERROR, error = message, sessionCode = "", qrPayload = "") }
    }
    fun showClientForm() { cancel(); _uiState.update { it.copy(mode = SyncMode.CLIENT_FORM) } }
    fun connectFromQr(raw: String) {
        when (val result = SyncQr.decode(raw)) {
            is SyncQr.DecodeResult.Ok -> connect(result.payload.ip, result.payload.code)
            is SyncQr.DecodeResult.Invalid -> { cancel(); showInputError(result.reason) }
        }
    }
    fun cancel() {
        // Publishing a new generation first also rejects a late old coroutine's CAS update.
        _uiState.value = SyncUiState(generation = generations.incrementAndGet())
        sessionJob?.cancel(); sessionJob = null
        resources.getAndSet(null)?.close()
    }
    fun dismissResult() = cancel()

    suspend fun storageInfo(): StorageInfo = withContext(Dispatchers.IO) {
        val context = getApplication<Application>()
        val dbFile = context.getDatabasePath("notessync.db")
        val attachmentsDir = java.io.File(context.filesDir, "attachments")
        val attachmentsBytes = attachmentsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        StorageInfo(if (dbFile.isFile) dbFile.length() else 0, attachmentsBytes, lastSyncAt)
    }
    override fun onStop(owner: LifecycleOwner) {
        if (uiState.value.mode in setOf(SyncMode.WAITING, SyncMode.SYNCING, SyncMode.CLIENT_FORM)) cancel()
    }
    override fun onCleared() { cancel(); super.onCleared() }
    companion object {
        const val SYNC_PORT = SyncQr.PORT
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val HANDSHAKE_TIMEOUT_MS = 5_000
        private const val IO_TIMEOUT_MS = 120_000
        private const val MAX_SESSION_MS = 10 * 60 * 1000L
        private const val MAX_FAILED_ATTEMPTS = 16
        private const val PREFS_NAME = "sync_prefs"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_LAST_SYNC = "last_sync_at"
    }
}

data class StorageInfo(val databaseBytes: Long, val attachmentBytes: Long, val lastSyncAt: Long)
