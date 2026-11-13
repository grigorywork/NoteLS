package com.notessync.app.sync.core

import com.notessync.app.sync.protocol.AttachmentDto
import com.notessync.app.sync.protocol.ChangeSet
import com.notessync.app.sync.protocol.FrameTransport

internal object SyncLimits {
    const val ATTACHMENT_BYTES = 32L * 1024 * 1024
    private val hash = Regex("[0-9a-f]{64}")
    fun attachment(meta: AttachmentDto) {
        require(FrameTransport.SAFE_ID.matches(meta.syncId) && meta.sizeBytes in 0..ATTACHMENT_BYTES && hash.matches(meta.sha256)) {
            "Invalid attachment metadata"
        }
    }
    fun changes(set: ChangeSet) {
        val seen = HashSet<String>()
        for (note in set.notes) for (meta in note.attachments) {
            attachment(meta)
            require(meta.noteSyncId == note.syncId && seen.add(meta.syncId)) { "Invalid attachment owner" }
        }
    }
}
