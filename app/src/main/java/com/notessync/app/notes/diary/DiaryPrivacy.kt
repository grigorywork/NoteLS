package com.notessync.app.notes.diary

import com.notessync.app.data.local.entity.NotebookEntity
import com.notessync.app.security.ProtectionSession
import java.util.Base64

/** The private marker never replaces the existing password gate. */
internal object DiaryPrivacy {
    fun valid(book: NotebookEntity): Boolean = book.isDiary && !book.isDeleted && book.isProtected &&
        validCredentials(book.passwordHash, book.passwordSalt)

    fun validCredentials(hash: String?, salt: String?): Boolean = runCatching {
        hash != null && salt != null && hash.length <= 64 && salt.length <= 32 &&
            Base64.getDecoder().decode(hash).size == 32 && Base64.getDecoder().decode(salt).size == 16
    }.getOrDefault(false)
}

/** Only IDs are retained. Activity backgrounding revokes grants and in-flight password work. */
internal object DiarySession {
    private val ids = mutableSetOf<Long>()
    @Volatile var generation: Long = 0L
        private set
    @Synchronized fun register(id: Long) { ids.add(id) }
    @Synchronized fun lock() {
        generation++
        ids.forEach(ProtectionSession::lockNotebook)
        ids.clear()
    }
}
