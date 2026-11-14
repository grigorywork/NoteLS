package com.notessync.app.data.local.model

import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.security.PasswordRecord

/** Credential binding only; password checks never load a complete task body or table document. */
data class TaskProtectionSnapshot(val id: Long, val syncId: String, val isTask: Boolean,
    val isDeleted: Boolean, val isPurged: Boolean, val isProtected: Boolean,
    val passwordHash: String?, val passwordSalt: String?)

internal fun TaskProtectionSnapshot.passwordRecord(): PasswordRecord? =
    if (isProtected && passwordHash != null && passwordSalt != null) PasswordRecord(passwordHash, passwordSalt) else null

internal fun NoteEntity.protectionSnapshot() = TaskProtectionSnapshot(id, syncId, isTask,
    isDeleted, isPurged, isProtected, passwordHash, passwordSalt)
