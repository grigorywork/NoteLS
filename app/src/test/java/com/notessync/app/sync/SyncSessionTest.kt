package com.notessync.app.sync

import com.notessync.app.sync.core.ApplyStats
import com.notessync.app.sync.core.ConflictResolver
import com.notessync.app.sync.core.Hashing
import com.notessync.app.sync.core.SyncDataSource
import com.notessync.app.sync.core.SyncResult
import com.notessync.app.sync.core.SyncSession
import com.notessync.app.sync.crypto.PairingHandshake
import com.notessync.app.sync.protocol.AttachmentDto
import com.notessync.app.sync.protocol.ChangeSet
import com.notessync.app.sync.protocol.FrameTransport
import com.notessync.app.sync.protocol.NoteDto
import com.notessync.app.sync.protocol.NotebookDto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** In-memory реализация SyncDataSource — «устройство» для тестов. */
class FakeSyncDataSource : SyncDataSource {

    val notes = mutableMapOf<String, NoteDto>()
    val notebooks = mutableMapOf<String, NotebookDto>()
    val files = mutableMapOf<String, ByteArray>()
    private val dirty = mutableSetOf<String>()

    fun putLocal(note: NoteDto, attachments: Map<String, ByteArray> = emptyMap()) {
        notes[note.syncId] = note
        dirty.add(note.syncId)
        files.putAll(attachments)
    }

    fun putLocal(notebook: NotebookDto) {
        notebooks[notebook.syncId] = notebook
        dirty.add(notebook.syncId)
    }

    override suspend fun ensureSyncIds() = Unit

    override suspend fun getChangesSince(since: Long): ChangeSet = ChangeSet(
        notebooks = notebooks.values.filter { it.syncId in dirty || it.updatedAt > since },
        notes = notes.values.filter { it.syncId in dirty || it.updatedAt > since }
            .map { note ->
                // Анонсируем только те вложения, байты которых реально есть
                // (как RoomSyncDataSource: нет файла — нет метаданных).
                note.copy(
                    attachments = note.attachments.mapNotNull { meta ->
                        files[meta.syncId]?.let { bytes ->
                            meta.copy(sizeBytes = bytes.size.toLong(), sha256 = Hashing.sha256Hex(bytes))
                        }
                    }
                )
            }
    )

    override suspend fun applyChanges(changes: ChangeSet): ApplyStats {
        var appliedNotebooks = 0
        var appliedNotes = 0
        var conflicts = 0
        for (dto in changes.notebooks) {
            val local = notebooks[dto.syncId]
            if (local == null || dto.updatedAt > local.updatedAt) {
                notebooks[dto.syncId] = dto
                appliedNotebooks++
            }
        }
        for (dto in changes.notes) {
            val local = notes[dto.syncId]
            when (val r = ConflictResolver.resolve(local, dto, localDirty = dto.syncId in dirty)) {
                is ConflictResolver.Resolution.ApplyRemote -> {
                    notes[dto.syncId] = dto
                    appliedNotes++
                }
                is ConflictResolver.Resolution.ApplyRemoteKeepConflictCopy -> {
                    notes[dto.syncId] = dto
                    // Конфликтная копия — новая локальная заметка, уедет при след. синхронизации.
                    val cloned = local!!.attachments.zip(r.conflictCopy.attachments).mapNotNull { (old, fresh) -> files[old.syncId]?.copyOf()?.let { fresh.syncId to it } }.toMap()
                    putLocal(r.conflictCopy, cloned)
                    appliedNotes++
                    conflicts++
                }
                ConflictResolver.Resolution.KeepLocal -> Unit
            }
        }
        val missing = changes.notes.flatMap { it.attachments }.filter { it.syncId !in files }
        return ApplyStats(appliedNotes, appliedNotebooks, conflicts, missing)
    }

    override suspend fun hasAttachmentBytes(attachmentSyncId: String): Boolean =
        attachmentSyncId in files

    override suspend fun readAttachmentBytes(attachmentSyncId: String): ByteArray? =
        files[attachmentSyncId]

    override suspend fun writeAttachmentBytes(meta: AttachmentDto, data: ByteArray) {
        files[meta.syncId] = data
    }

    override suspend fun markSynced(changeSet: ChangeSet) {
        dirty.removeAll(changeSet.notes.filter { notes[it.syncId]?.updatedAt == it.updatedAt }.map { it.syncId }.toSet())
        dirty.removeAll(changeSet.notebooks.filter { notebooks[it.syncId]?.updatedAt == it.updatedAt }.map { it.syncId }.toSet())
    }
}

/**
 * Сквозной тест: два «устройства» синхронизируются через настоящий
 * TCP-сокет (loopback) с шифрованием, без всякого Android.
 */
class SyncSessionTest {

    private val sessionCode = "a1".repeat(32)

    private fun note(id: String, title: String, updatedAt: Long, attachments: List<AttachmentDto> = emptyList()) =
        NoteDto(
            syncId = id, title = title, content = "Содержимое: $title",
            favorite = false, deleted = false, createdAt = 0, updatedAt = updatedAt,
            attachments = attachments
        )

    @Test
    fun `заметки и вложение синхронизируются в обе стороны, конфликт сохраняется`() {
        // --- Устройство A (сервер) ---
        val deviceA = FakeSyncDataSource()
        val bigFile = ByteArray(5 * 1024 * 1024) { (it * 31 % 251).toByte() } // 5 МБ
        val attachmentId = UUID.randomUUID().toString()
        val attachmentMeta = AttachmentDto(attachmentId, "note-a1", "image/png", bigFile.size.toLong(), "")
        deviceA.putLocal(note("note-a1", "Список покупок", 1000, listOf(attachmentMeta)), mapOf(attachmentId to bigFile))
        deviceA.putLocal(NotebookDto("nb-1", "Работа", deleted = false, updatedAt = 900))
        // Общая заметка, отредактированная на обоих устройствах; версия A новее.
        deviceA.putLocal(note("shared-1", "Версия A", 2000))

        // --- Устройство B (клиент) ---
        val deviceB = FakeSyncDataSource()
        deviceB.putLocal(note("note-b1", "Идеи", 1100))
        deviceB.putLocal(note("shared-1", "Версия B", 1500))

        // --- Синхронизация по loopback-сокету ---
        val serverSocket = ServerSocket(0)
        var serverResult: SyncResult? = null
        var serverError: Throwable? = null
        val serverThread = thread {
            try {
                val socket = serverSocket.accept()
                socket.soTimeout = 30_000
                runBlocking {
                    serverResult = SyncSession(
                        transport = FrameTransport(socket.getInputStream(), socket.getOutputStream(), PairingHandshake.establish(socket.getInputStream(), socket.getOutputStream(), sessionCode, client = false)),
                        dataSource = deviceA,
                        deviceId = "A", deviceName = "Устройство A",
                        lastSyncAt = { 0L }
                    ).runAsServer()
                }
                socket.close()
            } catch (t: Throwable) {
                serverError = t
            }
        }

        val clientSocket = Socket("127.0.0.1", serverSocket.localPort)
        clientSocket.soTimeout = 30_000
        val clientResult = runBlocking {
            SyncSession(
                transport = FrameTransport(clientSocket.getInputStream(), clientSocket.getOutputStream(), PairingHandshake.establish(clientSocket.getInputStream(), clientSocket.getOutputStream(), sessionCode, client = true)),
                dataSource = deviceB,
                deviceId = "B", deviceName = "Устройство B",
                lastSyncAt = { 0L }
            ).runAsClient()
        }
        clientSocket.close()
        serverThread.join(TimeUnit.SECONDS.toMillis(30))
        serverSocket.close()

        assertEquals(null, serverError?.let { "Сервер упал: $it" })
        assertNotNull(serverResult)

        // Заметка B появилась на A, заметка A — на B (аудит: «и наоборот»).
        assertEquals("Идеи", deviceA.notes["note-b1"]?.title)
        assertEquals("Список покупок", deviceB.notes["note-a1"]?.title)

        // Блокнот переехал на B.
        assertEquals("Работа", deviceB.notebooks["nb-1"]?.name)

        // Конфликт: победила более свежая версия A, локальная версия B сохранена копией.
        assertEquals("Версия A", deviceA.notes["shared-1"]?.title)
        assertEquals("Версия A", deviceB.notes["shared-1"]?.title)
        val conflictCopy = deviceB.notes.values.firstOrNull { it.title.endsWith(" (конфликт)") }
        assertNotNull("Конфликтная копия не сохранена", conflictCopy)
        assertEquals("Версия B (конфликт)", conflictCopy?.title)

        // Вложение 5 МБ доехало без искажений (аудит: «большие вложения без обрывов»).
        val received = deviceB.files[attachmentId]
        assertNotNull("Вложение не передано", received)
        assertEquals(Hashing.sha256Hex(bigFile), Hashing.sha256Hex(received!!))

        // Статистика согласована.
        assertTrue(clientResult.receivedNotes >= 2)
        assertTrue(serverResult!!.receivedAttachments == 0) // у B не было файлов для A
        assertTrue(clientResult.receivedAttachments == 1)
        assertTrue(clientResult.bytesReceived > bigFile.size) // файл + протокол
    }

    @Test
    fun `повторная синхронизация без изменений ничего не передаёт`() {
        val deviceA = FakeSyncDataSource()
        val deviceB = FakeSyncDataSource()
        deviceA.putLocal(note("n1", "Раз", 1000))

        fun syncOnce(): SyncResult {
            val server = ServerSocket(0)
            var result: SyncResult? = null
            val t = thread {
                val socket = server.accept()
                socket.soTimeout = 15_000
                runBlocking {
                    result = SyncSession(
                        FrameTransport(socket.getInputStream(), socket.getOutputStream(), PairingHandshake.establish(socket.getInputStream(), socket.getOutputStream(), sessionCode, client = false)),
                        deviceA, "A", "A", lastSyncAt = { 0L }
                    ).runAsServer()
                }
                socket.close()
            }
            val client = Socket("127.0.0.1", server.localPort)
            client.soTimeout = 15_000
            val r = runBlocking {
                SyncSession(
                    FrameTransport(client.getInputStream(), client.getOutputStream(), PairingHandshake.establish(client.getInputStream(), client.getOutputStream(), sessionCode, client = true)),
                    deviceB, "B", "B", lastSyncAt = { 0L }
                ).runAsClient()
            }
            client.close()
            t.join(15_000)
            server.close()
            return r
        }

        syncOnce() // первая: заметка уезжает на B
        // Вторая: A уже отметил заметку синхронизированной, B ничего нового не создавал.
        // Серверская сторона — то же устройство A с чистым dirty-флагом.
        val second = syncOnce()
        assertEquals(0, second.receivedNotes)
        assertEquals(0, second.receivedAttachments)
    }
}
