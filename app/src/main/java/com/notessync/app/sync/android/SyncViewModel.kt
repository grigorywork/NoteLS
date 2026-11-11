package com.notessync.app.sync.android

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.sync.core.SyncDataSource
import com.notessync.app.sync.core.SyncPhase
import com.notessync.app.sync.core.SyncProgress
import com.notessync.app.sync.core.SyncResult
import com.notessync.app.sync.core.SyncSession
import com.notessync.app.sync.crypto.SessionCrypto
import com.notessync.app.sync.protocol.FrameTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.UUID

enum class SyncMode { IDLE, WAITING, CLIENT_FORM, SYNCING, DONE, ERROR }

data class SyncUiState(
    val mode: SyncMode = SyncMode.IDLE,
    val localIp: String = "",
    val sessionCode: String = "",
    // Полезная нагрузка QR-кода сопряжения (Этап 4).
    val qrPayload: String = "",
    val peerName: String = "",
    val progress: SyncProgress = SyncProgress(SyncPhase.HANDSHAKE),
    val result: SyncResult? = null,
    val error: String? = null
)

class SyncViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val dataSource: SyncDataSource =
        RoomSyncDataSource(application, AppDatabase.get(application))

    private val _uiState = MutableStateFlow(SyncUiState())
    val uiState: StateFlow<SyncUiState> = _uiState.asStateFlow()

    private var sessionJob: Job? = null
    private var serverSocket: ServerSocket? = null
    @Volatile private var cancelled = false

    val deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, null)
            ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_DEVICE_ID, it).apply() }

    val lastSyncAt: Long
        get() = prefs.getLong(KEY_LAST_SYNC, 0L)

    /** «Ведущее» устройство: слушает порт и ждёт подключения. */
    fun startWaiting() {
        cancelled = false
        val code = generateSessionCode()
        val ip = localIpAddress()
        val qrPayload = com.notessync.app.sync.qr.SyncQr.encode(ip, SYNC_PORT, code)
        _uiState.update {
            it.copy(mode = SyncMode.WAITING, sessionCode = code, localIp = ip, qrPayload = qrPayload, error = null)
        }
        sessionJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                ServerSocket(SYNC_PORT).use { server ->
                    serverSocket = server
                    val socket = server.accept()
                    serverSocket = null
                    runSession(socket, code) { session, _ ->
                        session.runAsServer()
                    }
                }
            }.onFailure { if (it !is kotlinx.coroutines.CancellationException && !cancelled) showError(it) }
        }
    }

    /** «Ведомое» устройство: подключается по адресу и коду с экрана. */
    fun connect(ip: String, code: String) {
        cancelled = false
        _uiState.update { it.copy(mode = SyncMode.SYNCING, error = null) }
        sessionJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val socket = Socket()
                socket.connect(InetSocketAddress(ip.trim(), SYNC_PORT), CONNECT_TIMEOUT_MS)
                socket.soTimeout = IO_TIMEOUT_MS
                runSession(socket, code.trim()) { session, _ ->
                    session.runAsClient()
                }
            }.onFailure { showError(it) }
        }
    }

    private suspend fun runSession(
        socket: Socket,
        code: String,
        block: suspend (SyncSession, FrameTransport) -> SyncResult
    ) {
        _uiState.update { it.copy(mode = SyncMode.SYNCING) }
        socket.soTimeout = IO_TIMEOUT_MS
        socket.use {
            val transport = FrameTransport(
                socket.getInputStream(),
                socket.getOutputStream(),
                SessionCrypto(code)
            )
            val sessionRef = java.util.concurrent.atomic.AtomicReference<SyncSession>()
            val session = SyncSession(
                transport = transport,
                dataSource = dataSource,
                deviceId = deviceId,
                deviceName = android.os.Build.MODEL,
                lastSyncAt = { lastSyncAt },
                onProgress = { progress ->
                    val peer = sessionRef.get()?.peerName.orEmpty()
                    _uiState.update { s ->
                        s.copy(progress = progress, peerName = peer.ifBlank { s.peerName })
                    }
                }
            )
            sessionRef.set(session)
            val result = block(session, transport)
            prefs.edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).apply()
            _uiState.update { it.copy(mode = SyncMode.DONE, result = result, peerName = session.peerName) }
        }
    }

    /** Переход к форме ручного ввода адреса и кода. */
    fun showClientForm() {
        _uiState.update { it.copy(mode = SyncMode.CLIENT_FORM, error = null) }
    }

    /** Подключение по отсканированному QR-коду (Этап 4). */
    fun connectFromQr(raw: String) {
        when (val result = com.notessync.app.sync.qr.SyncQr.decode(raw)) {
            is com.notessync.app.sync.qr.SyncQr.DecodeResult.Ok ->
                connect(result.payload.ip, result.payload.code)
            is com.notessync.app.sync.qr.SyncQr.DecodeResult.Invalid ->
                _uiState.update { it.copy(mode = SyncMode.ERROR, error = result.reason) }
        }
    }

    fun cancel() {
        cancelled = true
        sessionJob?.cancel()
        runCatching { serverSocket?.close() }
        serverSocket = null
        _uiState.update { it.copy(mode = SyncMode.IDLE, error = null) }
    }

    fun dismissResult() {
        _uiState.value = SyncUiState()
    }

    private fun showError(error: Throwable) {
        _uiState.update {
            it.copy(
                mode = SyncMode.ERROR,
                error = error.message ?: error.javaClass.simpleName
            )
        }
    }

    /** Размер хранилища для экрана «О хранилище»: база данных и вложения. */
    fun storageInfo(): StorageInfo {
        val context = getApplication<Application>()
        val dbFile = context.getDatabasePath("notessync.db")
        val attachmentsDir = java.io.File(context.filesDir, "attachments")
        val attachmentsBytes = attachmentsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return StorageInfo(
            databaseBytes = if (dbFile.isFile) dbFile.length() else 0,
            attachmentBytes = attachmentsBytes,
            lastSyncAt = lastSyncAt
        )
    }

    private fun generateSessionCode(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // без похожих символов 0/O, 1/I
        val random = SecureRandom()
        return (1..SESSION_CODE_LENGTH).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }

    private fun localIpAddress(): String {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return ""
        for (iface in interfaces) {
            if (!iface.isUp || iface.isLoopback) continue
            for (address in iface.inetAddresses) {
                if (address is Inet4Address && !address.isLoopbackAddress) {
                    return address.hostAddress.orEmpty()
                }
            }
        }
        return ""
    }

    override fun onCleared() {
        cancel()
        super.onCleared()
    }

    companion object {
        const val SYNC_PORT = 47383
        private const val SESSION_CODE_LENGTH = 8
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val IO_TIMEOUT_MS = 120_000
        private const val PREFS_NAME = "sync_prefs"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_LAST_SYNC = "last_sync_at"
    }
}

data class StorageInfo(
    val databaseBytes: Long,
    val attachmentBytes: Long,
    val lastSyncAt: Long
)
