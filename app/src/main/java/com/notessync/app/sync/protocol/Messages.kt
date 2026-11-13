package com.notessync.app.sync.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

// ---------- Модели данных, которыми обмениваются устройства ----------

@Serializable
data class NotebookDto(
    val syncId: String,
    val name: String,
    val deleted: Boolean,
    val updatedAt: Long,
    val isProtected: Boolean = false,
    val passwordHash: String? = null,
    val passwordSalt: String? = null
)

@Serializable
data class AttachmentDto(
    val syncId: String,
    val noteSyncId: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    val displayWidthDp: Int? = null,
    val displayHeightDp: Int? = null
)

/** Absent wrapper: legacy peer. Present wrapper with null at: explicit deadline removal. */
@Serializable
data class TaskDeadlineDto(val at: Long? = null)

@Serializable
data class NoteDto(
    val syncId: String,
    val title: String,
    val content: String,
    val favorite: Boolean,
    val deleted: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val notebookSyncId: String? = null,
    val tagNames: List<String> = emptyList(),
    val attachments: List<AttachmentDto> = emptyList(),
    val reminderAt: Long? = null,
    val dayKey: Long? = null,
    val isTask: Boolean = false,
    val isCompleted: Boolean = false,
    val taskColor: String = "violet",
    val taskPriority: Int = 0,
    val textFormat: String = "",
    // Null: legacy peer without tables support. Empty string: explicit table removal.
    val tablesData: String? = null,
    val taskElapsedMillis: Long = 0L,
    val taskTimerStartedAt: Long? = null,
    // Аудит 2026-11-11: обратный отсчёт и дата выполнения тоже синхронизируются.
    // Поля с значениями по умолчанию: старые сборки игнорируют их (ignoreUnknownKeys),
    // новые читают данные старых как «таймера нет». Протокол2 обязателен для безопасного окончательного удаления.
    val taskCountdownMillis: Long = 0L,
    val taskCountdownEndAt: Long? = null,
    val taskCompletedAt: Long? = null,
    val isProtected: Boolean = false,
    val passwordHash: String? = null,
    val passwordSalt: String? = null,
    val pinned: Boolean? = null,
    val purged: Boolean? = null,
    val noteColor: String? = null,
    val taskDeadline: TaskDeadlineDto? = null
)

// Пакет изменений: блокноты + заметки (теги едут внутри заметок по именам).
@Serializable
data class ChangeSet(
    val notebooks: List<NotebookDto> = emptyList(),
    val notes: List<NoteDto> = emptyList()
) {
    val totalAttachmentBytes: Long
        get() = notes.sumOf { n -> n.attachments.sumOf { it.sizeBytes } }
}

// ---------- Сообщения протокола ----------

@Serializable
sealed interface SyncMessage {

    @Serializable
    @SerialName("hello")
    data class Hello(
        val deviceId: String,
        val deviceName: String,
        val protocol: Int = PROTOCOL_VERSION
    ) : SyncMessage

    @Serializable
    @SerialName("changes")
    data class Changes(val set: ChangeSet) : SyncMessage

    @Serializable
    @SerialName("changes_done")
    data object ChangesDone : SyncMessage

    @Serializable
    @SerialName("attachment_request")
    data class AttachmentRequest(val syncId: String) : SyncMessage

    @Serializable
    @SerialName("done")
    data class Done(
        val appliedNotes: Int,
        val appliedNotebooks: Int,
        val conflicts: Int,
        val receivedAttachments: Int
    ) : SyncMessage

    @Serializable
    @SerialName("error")
    data class Error(val message: String) : SyncMessage

    companion object {
        const val PROTOCOL_VERSION = 2

        val json: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun encode(message: SyncMessage): ByteArray =
            json.encodeToString(SyncMessage.serializer(), message).toByteArray(Charsets.UTF_8)

        fun decode(bytes: ByteArray): SyncMessage =
            json.decodeFromString(SyncMessage.serializer(), Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString())
    }
}
