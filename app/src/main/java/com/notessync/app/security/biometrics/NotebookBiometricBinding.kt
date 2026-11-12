package com.notessync.app.security.biometrics

import com.notessync.app.data.local.entity.NotebookEntity
import com.notessync.app.sync.core.Hashing

/** Local permission is bound to both the notebook identity and the current password verifier. */
data class NotebookBiometricBinding(val syncId: String, val credentialTag: String) {
    companion object {
        fun from(notebook: NotebookEntity): NotebookBiometricBinding? {
            if (notebook.isDeleted || !notebook.isProtected) return null
            val hash = notebook.passwordHash ?: return null
            val salt = notebook.passwordSalt ?: return null
            val source = listOf(notebook.syncId, hash, salt).joinToString("") { "${it.length}:$it" }
            return NotebookBiometricBinding(notebook.syncId, Hashing.sha256Hex(source.toByteArray(Charsets.UTF_8)))
        }
    }
}
