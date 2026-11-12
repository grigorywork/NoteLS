package com.notessync.app.sync.core

import com.notessync.app.sync.protocol.AttachmentDto
import com.notessync.app.sync.protocol.FrameTransport
import com.notessync.app.sync.protocol.FrameTransport.Companion.CHUNK_BYTES
import com.notessync.app.sync.protocol.FrameTransport.Frame
import com.notessync.app.sync.protocol.SyncMessage
import java.io.ByteArrayOutputStream

class SyncException(message: String) : Exception(message)

/** Потолок стартового буфера вложения: реальный размер проверяется по SHA-256. */
private const val MAX_INITIAL_BUFFER_BYTES = 8L * 1024 * 1024

enum class SyncPhase {
    HANDSHAKE,            // рукопожатие
    EXCHANGING_CHANGES,   // обмен метаданными заметок/блокнотов
    RECEIVING_FILES,      // приём вложений
    SENDING_FILES,        // отправка вложений по запросам peer
    FINISHING,            // финальные подтверждения
    COMPLETED
}

data class SyncProgress(
    val phase: SyncPhase,
    val attachmentsDone: Int = 0,
    val attachmentsTotal: Int = 0,
    val attachmentBytesReceived: Long = 0,
    val attachmentBytesExpected: Long = 0
)

/**
 * Одна сессия синхронизации между двумя устройствами.
 * Обмен полудуплексный и пофазный, поэтому одна корутина на сторону, без гонок:
 *   1. HELLO обе стороны (проверка версии протокола);
 *   2. сервер → клиент: свои изменения;
 *   3. клиент → сервер: свои изменения;
 *   4. клиент запрашивает недостающие вложения, сервер отдаёт;
 *   5. сервер запрашивает недостающие вложения, клиент отдаёт;
 *   6. DONE обе стороны со статистикой.
 */
class SyncSession(
    private val transport: FrameTransport,
    private val dataSource: SyncDataSource,
    private val deviceId: String,
    private val deviceName: String,
    private val lastSyncAt: () -> Long,
    private val onProgress: (SyncProgress) -> Unit = {}
) {
    var peerName: String = ""
        private set

    // ---------- Сервер (устройство, которое ждёт подключения) ----------

    suspend fun runAsServer(): SyncResult {
        dataSource.ensureSyncIds()
        handshake()
        try {
            // Фаза 2: отдаём свои изменения.
            val sent = sendMyChanges()
            // Фаза 3: принимаем изменения клиента.
            val applied = receiveChanges()
            // Фаза 4: обслуживаем запросы клиента на вложения.
            val sentFiles = serveAttachmentRequests()
            // Фаза 5: запрашиваем свои недостающие вложения.
            val gotFiles = requestAttachments(applied.missingAttachments)
            signalRequestsFinished()
            // Фаза 6: финал.
            return finish(sent, applied.copy(missingAttachments = emptyList()), sentFiles, gotFiles)
        } catch (e: Exception) {
            fail(e)
        }
    }

    // ---------- Клиент (устройство, которое подключается) ----------

    suspend fun runAsClient(): SyncResult {
        dataSource.ensureSyncIds()
        handshake()
        try {
            // Фаза 2: принимаем изменения сервера.
            val applied = receiveChanges()
            // Фаза 3: отдаём свои изменения.
            val sent = sendMyChanges()
            // Фаза 4: запрашиваем недостающие вложения.
            val gotFiles = requestAttachments(applied.missingAttachments)
            signalRequestsFinished()
            // Фаза 5: обслуживаем запросы сервера.
            val sentFiles = serveAttachmentRequests()
            // Фаза 6: финал.
            return finish(sent, applied.copy(missingAttachments = emptyList()), sentFiles, gotFiles)
        } catch (e: Exception) {
            fail(e)
        }
    }

    // ---------- Фазы ----------

    private fun handshake() {
        onProgress(SyncProgress(SyncPhase.HANDSHAKE))
        transport.sendMessage(SyncMessage.Hello(deviceId = deviceId, deviceName = deviceName))
        when (val msg = readMessage()) {
            is SyncMessage.Hello -> {
                if (msg.protocol != SyncMessage.PROTOCOL_VERSION) {
                    throw SyncException("Обновите NoteLS на обоих устройствах (протокол ${msg.protocol})")
                }
                peerName = msg.deviceName
            }
            is SyncMessage.Error -> throw SyncException("Другая сторона: ${msg.message}")
            else -> throw SyncException("Ожидалось приветствие")
        }
    }

    private suspend fun sendMyChanges(): Int {
        onProgress(SyncProgress(SyncPhase.EXCHANGING_CHANGES))
        val changes = dataSource.getChangesSince(lastSyncAt())
        transport.sendMessage(SyncMessage.Changes(changes))
        transport.sendMessage(SyncMessage.ChangesDone)
        dataSource.markSynced(changes)
        return changes.notes.size
    }

    private suspend fun receiveChanges(): ApplyStats {
        onProgress(SyncProgress(SyncPhase.EXCHANGING_CHANGES))
        return when (val msg = readMessage()) {
            is SyncMessage.Changes -> {
                val stats = dataSource.applyChanges(msg.set)
                when (val done = readMessage()) {
                    is SyncMessage.ChangesDone -> stats
                    else -> throw SyncException("Ожидался конец пакета изменений, получено: $done")
                }
            }
            is SyncMessage.Error -> throw SyncException("Другая сторона: ${msg.message}")
            else -> throw SyncException("Ожидался пакет изменений")
        }
    }

    /** Запрашивает у peer перечисленные вложения и сохраняет их. */
    private suspend fun requestAttachments(missing: List<AttachmentDto>): Int {
        if (missing.isEmpty()) return 0
        val totalBytes = missing.sumOf { it.sizeBytes }
        var receivedBytes = 0L
        var done = 0
        for (meta in missing) {
            onProgress(
                SyncProgress(
                    SyncPhase.RECEIVING_FILES,
                    attachmentsDone = done,
                    attachmentsTotal = missing.size,
                    attachmentBytesReceived = receivedBytes,
                    attachmentBytesExpected = totalBytes
                )
            )
            transport.sendMessage(SyncMessage.AttachmentRequest(meta.syncId))
            // Аудит 2026-11-11: sizeBytes заявлен пиром — доверять ему при выделении
            // буфера нельзя (заявленные гигабайты давали бы OOM). Буфер растёт сам.
            val buffer = ByteArrayOutputStream(
                meta.sizeBytes.coerceIn(0L, MAX_INITIAL_BUFFER_BYTES).toInt()
            )
            while (true) {
                when (val frame = transport.readFrame() ?: throw SyncException("Соединение закрыто при приёме вложения")) {
                    is Frame.Chunk -> {
                        if (frame.attachmentSyncId != meta.syncId) {
                            throw SyncException("Пришёл кусок чужого вложения")
                        }
                        buffer.write(frame.data)
                        receivedBytes += frame.data.size
                        if (frame.last) break
                    }
                    is Frame.Message -> {
                        if (frame.message is SyncMessage.Error) {
                            throw SyncException("Другая сторона: ${frame.message.message}")
                        }
                        throw SyncException("Неожиданное сообщение при приёме вложения")
                    }
                }
            }
            val data = buffer.toByteArray()
            if (Hashing.sha256Hex(data) != meta.sha256) {
                throw SyncException("Вложение ${meta.syncId} пришло с ошибкой (контрольная сумма)")
            }
            dataSource.writeAttachmentBytes(meta, data)
            done++
        }
        onProgress(
            SyncProgress(
                SyncPhase.RECEIVING_FILES,
                attachmentsDone = done,
                attachmentsTotal = missing.size,
                attachmentBytesReceived = receivedBytes,
                attachmentBytesExpected = totalBytes
            )
        )
        return done
    }

    /** Отдаёт вложения по запросам peer до сигнала «запросы закончились». */
    private suspend fun serveAttachmentRequests(): Int {
        var sent = 0
        while (true) {
            when (val frame = transport.readFrame() ?: throw SyncException("Соединение закрыто")) {
                is Frame.Message -> when (val msg = frame.message) {
                    is SyncMessage.AttachmentRequest -> {
                        onProgress(SyncProgress(SyncPhase.SENDING_FILES, attachmentsDone = sent))
                        val bytes = dataSource.readAttachmentBytes(msg.syncId)
                        if (bytes == null) {
                            transport.sendMessage(SyncMessage.Error("Нет файла вложения ${msg.syncId}"))
                        } else {
                            streamAttachment(msg.syncId, bytes)
                            sent++
                        }
                    }
                    // Пустой пакет-разделитель: peer закончил запрашивать.
                    is SyncMessage.ChangesDone -> return sent
                    is SyncMessage.Error -> throw SyncException("Другая сторона: ${msg.message}")
                    else -> throw SyncException("Неожиданное сообщение: $msg")
                }
                is Frame.Chunk -> throw SyncException("Неожиданные бинарные данные")
            }
        }
    }

    private fun streamAttachment(syncId: String, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val end = (offset + CHUNK_BYTES).coerceAtMost(bytes.size)
            transport.sendChunk(syncId, bytes.copyOfRange(offset, end), last = end == bytes.size)
            offset = end
        }
        if (bytes.isEmpty()) transport.sendChunk(syncId, ByteArray(0), last = true)
    }

    /** Маркер «мои запросы закончились» — используем ChangesDone как разделитель. */
    private fun signalRequestsFinished() {
        transport.sendMessage(SyncMessage.ChangesDone)
    }

    private fun finish(sentNotes: Int, applied: ApplyStats, sentFiles: Int, gotFiles: Int): SyncResult {
        onProgress(SyncProgress(SyncPhase.FINISHING))
        transport.sendMessage(
            SyncMessage.Done(
                appliedNotes = applied.appliedNotes,
                appliedNotebooks = applied.appliedNotebooks,
                conflicts = applied.conflicts,
                receivedAttachments = gotFiles
            )
        )
        when (val msg = readMessage()) {
            is SyncMessage.Done -> Unit
            is SyncMessage.Error -> throw SyncException("Другая сторона: ${msg.message}")
            else -> throw SyncException("Ожидалось финальное подтверждение")
        }
        onProgress(SyncProgress(SyncPhase.COMPLETED))
        return SyncResult(
            sentNotes = sentNotes,
            receivedNotes = applied.appliedNotes,
            conflicts = applied.conflicts,
            sentAttachments = sentFiles,
            receivedAttachments = gotFiles,
            bytesSent = transport.bytesSent.get(),
            bytesReceived = transport.bytesReceived.get()
        )
    }

    private fun readMessage(): SyncMessage {
        return when (val frame = transport.readFrame() ?: throw SyncException("Соединение закрыто")) {
            is Frame.Message -> frame.message
            is Frame.Chunk -> throw SyncException("Неожиданные бинарные данные")
        }
    }

    private fun fail(cause: Exception): Nothing {
        runCatching { transport.sendMessage(SyncMessage.Error(cause.message ?: "Ошибка")) }
        throw cause
    }
}
