package com.notessync.app.sync.core

import com.notessync.app.i18n.uiText

import com.notessync.app.sync.protocol.AttachmentDto
import com.notessync.app.sync.protocol.FrameTransport
import com.notessync.app.sync.protocol.FrameTransport.Companion.CHUNK_BYTES
import com.notessync.app.sync.protocol.FrameTransport.Frame
import com.notessync.app.sync.protocol.SyncMessage
import java.io.EOFException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class SyncException(message: String) : Exception(message)

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
    private val onProgress: (SyncProgress) -> Unit = {},
    private val expectedPeerId: String? = null,
    private val trustedPairing: Boolean = false,
    private val onPeerValidated: suspend (SyncMessage.Hello) -> Unit = {}
) {
    var peerName: String = ""
        private set
    var peerId: String = ""
        private set
    var peerSupportsTrust: Boolean = false
        private set

    private val offeredAttachments = mutableSetOf<String>()
    private var pendingAcknowledgements = SyncAcknowledgements()

    // ---------- Сервер (устройство, которое ждёт подключения) ----------

    suspend fun runAsServer(): SyncResult {
        handshake()
        dataSource.ensureSyncIds()
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
        handshake()
        dataSource.ensureSyncIds()
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

    private suspend fun handshake() {
        onProgress(SyncProgress(SyncPhase.HANDSHAKE))
        transport.sendMessage(SyncMessage.Hello(deviceId = deviceId, deviceName = deviceName, trustedPairing = trustedPairing))
        when (val msg = readMessage()) {
            is SyncMessage.Hello -> {
                if (msg.protocol != SyncMessage.PROTOCOL_VERSION) {
                    throw SyncException(uiText("Обновите NoteLS на обоих устройствах (протокол %1\$s)", (msg.protocol)))
                }
                require(msg.deviceId.isNotBlank() && msg.deviceId.length <= 128 && msg.deviceName.length <= 128) { "Invalid peer identity" }
                require(msg.deviceId != deviceId && (expectedPeerId == null || expectedPeerId == msg.deviceId)) { "Unexpected peer identity" }
                onPeerValidated(msg)
                peerName = msg.deviceName
                peerId = msg.deviceId
                peerSupportsTrust = msg.trustedPairing
            }
            is SyncMessage.Error -> throw SyncException(uiText("Другая сторона: %1\$s", (msg.message)))
            else -> throw SyncException(uiText("Ожидалось приветствие"))
        }
    }

    private suspend fun sendMyChanges(): Int {
        onProgress(SyncProgress(SyncPhase.EXCHANGING_CHANGES))
        val changes = dataSource.getChangesSince(lastSyncAt())
        SyncLimits.changes(changes)
        for (note in changes.notes) for (attachment in note.attachments) offeredAttachments.add(attachment.syncId)
        transport.sendMessage(SyncMessage.Changes(changes))
        transport.sendMessage(SyncMessage.ChangesDone)
        pendingAcknowledgements = SyncAcknowledgements(changes.notes.map { SyncVersion(it.syncId, it.updatedAt) },
            changes.notebooks.map { SyncVersion(it.syncId, it.updatedAt) })
        return changes.notes.size
    }

    private suspend fun receiveChanges(): ApplyStats {
        onProgress(SyncProgress(SyncPhase.EXCHANGING_CHANGES))
        return when (val msg = readMessage()) {
            is SyncMessage.Changes -> {
                SyncLimits.changes(msg.set)
                when (readMessage()) {
                    is SyncMessage.ChangesDone -> dataSource.applyChanges(msg.set)
                    else -> throw SyncException(uiText("Синхронизация прервана"))
                }
            }
            is SyncMessage.Error -> throw SyncException(uiText("Другая сторона: %1\$s", (msg.message)))
            else -> throw SyncException(uiText("Ожидался пакет изменений"))
        }
    }

    /** Запрашивает у peer перечисленные вложения и сохраняет их. */
    private suspend fun requestAttachments(missing: List<AttachmentDto>): Int {
        if (missing.isEmpty()) return 0
        missing.forEach(SyncLimits::attachment)
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
            var fileBytes = 0L
            dataSource.receiveTransferAttachment(meta).use { sink ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    when (val frame = transport.readFrame() ?: throw SyncException(uiText("Соединение закрыто при приёме вложения"))) {
                        is Frame.Chunk -> {
                            if (frame.attachmentSyncId != meta.syncId) throw SyncException(uiText("Пришёл кусок чужого вложения"))
                            require(frame.data.size.toLong() <= meta.sizeBytes - fileBytes) { "Attachment exceeds declared size" }
                            sink.output.write(frame.data)
                            fileBytes += frame.data.size; receivedBytes += frame.data.size
                            if (frame.last) break
                        }
                        is Frame.Message -> {
                            if (frame.message is SyncMessage.Error) throw SyncException(uiText("Другая сторона: %1\$s", frame.message.message))
                            throw SyncException(uiText("Неожиданное сообщение при приёме вложения"))
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                sink.commit() // The sink checks exact size/SHA before its atomic publication.
            }
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
            when (val frame = transport.readFrame() ?: throw SyncException(uiText("Соединение закрыто"))) {
                is Frame.Message -> when (val msg = frame.message) {
                    is SyncMessage.AttachmentRequest -> {
                        require(offeredAttachments.remove(msg.syncId)) { "Attachment was not offered or was already requested" }
                        onProgress(SyncProgress(SyncPhase.SENDING_FILES, attachmentsDone = sent))
                        val source = dataSource.openTransferAttachment(msg.syncId)
                        if (source == null) {
                            transport.sendMessage(SyncMessage.Error(uiText("Нет файла вложения %1\$s", (msg.syncId))))
                        } else {
                            source.use { streamAttachment(msg.syncId, it) }
                            sent++
                        }
                    }
                    // Пустой пакет-разделитель: peer закончил запрашивать.
                    is SyncMessage.ChangesDone -> return sent
                    is SyncMessage.Error -> throw SyncException(uiText("Другая сторона: %1\$s", (msg.message)))
                    else -> throw SyncException(uiText("Неожиданное сообщение: %1\$s", (msg)))
                }
                is Frame.Chunk -> throw SyncException(uiText("Неожиданные бинарные данные"))
            }
        }
    }

    private suspend fun streamAttachment(syncId: String, source: SyncAttachmentSource) {
        require(source.sizeBytes in 0..SyncLimits.ATTACHMENT_BYTES) { "Attachment too large" }
        if (source.sizeBytes == 0L) {
            require(source.input.read() == -1) { "Attachment size changed" }
            transport.sendChunk(syncId, ByteArray(0), last = true)
            return
        }
        val buffer = ByteArray(minOf(CHUNK_BYTES.toLong(), source.sizeBytes).toInt())
        var remaining = source.sizeBytes
        while (remaining > 0) {
            currentCoroutineContext().ensureActive()
            val wanted = minOf(buffer.size.toLong(), remaining).toInt()
            var offset = 0
            while (offset < wanted) {
                currentCoroutineContext().ensureActive()
                val count = source.input.read(buffer, offset, wanted - offset)
                if (count < 0) throw EOFException("Attachment was truncated")
                if (count == 0) {
                    val one = source.input.read()
                    if (one < 0) throw EOFException("Attachment was truncated")
                    buffer[offset++] = one.toByte()
                } else offset += count
            }
            remaining -= wanted
            if (remaining == 0L) require(source.input.read() == -1) { "Attachment size changed" }
            transport.sendChunk(syncId, if (wanted == buffer.size) buffer else buffer.copyOf(wanted), last = remaining == 0L)
        }
    }

    /** Маркер «мои запросы закончились» — используем ChangesDone как разделитель. */
    private fun signalRequestsFinished() {
        transport.sendMessage(SyncMessage.ChangesDone)
    }

    private suspend fun finish(sentNotes: Int, applied: ApplyStats, sentFiles: Int, gotFiles: Int): SyncResult {
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
            is SyncMessage.Error -> throw SyncException(uiText("Другая сторона: %1\$s", (msg.message)))
            else -> throw SyncException(uiText("Ожидалось финальное подтверждение"))
        }
        dataSource.markAcknowledged(pendingAcknowledgements)
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
        return when (val frame = transport.readFrame() ?: throw SyncException(uiText("Соединение закрыто"))) {
            is Frame.Message -> frame.message
            is Frame.Chunk -> throw SyncException(uiText("Неожиданные бинарные данные"))
        }
    }

    private fun fail(cause: Exception): Nothing {
        runCatching { transport.sendMessage(SyncMessage.Error(uiText("Синхронизация прервана"))) }
        throw cause
    }
}
