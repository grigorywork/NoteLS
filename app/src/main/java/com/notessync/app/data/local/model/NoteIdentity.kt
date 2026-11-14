package com.notessync.app.data.local.model

/** Navigation/Undo saves only an identity pair, never document text or password material. */
data class NoteIdentity(val id: Long, val syncId: String) : java.io.Serializable
