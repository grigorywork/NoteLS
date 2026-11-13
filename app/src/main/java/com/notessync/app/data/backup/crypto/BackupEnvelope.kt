package com.notessync.app.data.backup.crypto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

@Serializable
internal data class BackupHeader(val identity: BackupIdentity, val createdAt: Long, val wrappedKey: String, val nonce: String) {
    fun validate() {
        identity.validate(); require(createdAt > 0)
        require(BackupIdentity.decode(wrappedKey).size == 256 && BackupIdentity.decode(nonce).size == 8)
    }
}
internal data class BackupEnvelope(val header: BackupHeader, val aad: ByteArray)

/** Binary framing is independent of Android. Exact encoded header bytes are bound to every AEAD record. */
internal object BackupEnvelopeCodec {
    val MAGIC = "NoteLSB1".toByteArray(Charsets.US_ASCII)
    private const val MAX_HEADER = 16 * 1024
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    fun write(output: OutputStream, header: BackupHeader): BackupEnvelope {
        header.validate()
        val bytes = json.encodeToString(BackupHeader.serializer(), header).toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_HEADER)
        DataOutputStream(output).apply { write(MAGIC); writeInt(bytes.size); write(bytes) }
        return BackupEnvelope(header, digest(bytes))
    }
    fun read(input: InputStream): BackupEnvelope {
        val data = DataInputStream(input); val magic = ByteArray(MAGIC.size); data.readFully(magic)
        require(magic.contentEquals(MAGIC)) { "Unsupported encrypted backup" }
        val size = data.readInt(); require(size in 1..MAX_HEADER)
        val bytes = ByteArray(size); data.readFully(bytes)
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        val header = json.decodeFromString(BackupHeader.serializer(), decoder.decode(ByteBuffer.wrap(bytes)).toString())
        header.validate()
        return BackupEnvelope(header, digest(bytes))
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(MAGIC + bytes)
}
