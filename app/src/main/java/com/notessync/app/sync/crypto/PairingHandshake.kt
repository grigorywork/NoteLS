package com.notessync.app.sync.crypto

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

/**
 * Mandatory security envelope 3, independent of JSON data protocol 2.
 * Fresh P-256 ECDH, QR PSK authentication of both roles/transcript, RFC5869 traffic keys.
 * No legacy downgrade. Streams must have an owning socket timeout/cancellation deadline.
 */
object PairingHandshake {
    const val VERSION = 3
    private val magic = "NoteLS3\n".toByteArray(Charsets.US_ASCII)
    private val random = SecureRandom()
    private const val NONCE_BYTES = 32
    private const val MAX_PUBLIC_BYTES = 128

    fun establish(input: InputStream, output: OutputStream, secret: String, client: Boolean): SessionCrypto {
        val psk = PairingSecret.bytes(secret)
        val pair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"), random); generateKeyPair()
        }
        val own = Hello(ByteArray(NONCE_BYTES).also(random::nextBytes), pair.public.encoded)
        val reader = DataInputStream(input)
        val writer = DataOutputStream(output)
        var shared: ByteArray? = null
        var root: ByteArray? = null
        try {
            val peer = if (client) { writeHello(writer, own); readHello(reader) } else readHello(reader)
            val c = if (client) own else peer
            val s = if (client) peer else own
            val transcript = magic + encoded(c) + encoded(s)
            fun proof(role: String) = Hkdf.hmac(psk, "NoteLS3/$role".toByteArray(Charsets.US_ASCII) + transcript)
            if (client) {
                val actual = ByteArray(32).also(reader::readFully)
                require(MessageDigest.isEqual(proof("server"), actual)) { "Pairing authentication failed" }
                writer.write(proof("client")); writer.flush()
            } else {
                writeHello(writer, own)
                writer.write(proof("server")); writer.flush()
                val actual = ByteArray(32).also(reader::readFully)
                require(MessageDigest.isEqual(proof("client"), actual)) { "Pairing authentication failed" }
            }
            val public = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(peer.public)) as ECPublicKey
            val ownPublic = pair.public as ECPublicKey
            require(public.params.curve == ownPublic.params.curve && public.params.generator == ownPublic.params.generator &&
                public.params.order == ownPublic.params.order && public.params.cofactor == ownPublic.params.cofactor) { "Invalid peer key" }
            shared = KeyAgreement.getInstance("ECDH").run { init(pair.private); doPhase(public, true); generateSecret() }
            val combined = shared + psk
            root = try { Hkdf.extract(MessageDigest.getInstance("SHA-256").digest(transcript), combined) }
                finally { combined.fill(0) }
            return SessionCrypto.derive(root, client)
        } finally { psk.fill(0); shared?.fill(0); root?.fill(0) }
    }

    private data class Hello(val nonce: ByteArray, val public: ByteArray)
    private fun encoded(hello: Hello): ByteArray = hello.nonce + byteArrayOf(
        (hello.public.size ushr 8).toByte(), hello.public.size.toByte()) + hello.public
    private fun writeHello(writer: DataOutputStream, hello: Hello) {
        writer.write(magic); writer.write(encoded(hello)); writer.flush()
    }
    private fun readHello(reader: DataInputStream): Hello {
        val actual = ByteArray(magic.size).also(reader::readFully)
        require(actual.contentEquals(magic)) { "Update NoteLS on both devices" }
        val nonce = ByteArray(NONCE_BYTES).also(reader::readFully)
        val length = reader.readUnsignedShort()
        require(length in 1..MAX_PUBLIC_BYTES) { "Invalid peer key size" }
        return Hello(nonce, ByteArray(length).also(reader::readFully))
    }
}
