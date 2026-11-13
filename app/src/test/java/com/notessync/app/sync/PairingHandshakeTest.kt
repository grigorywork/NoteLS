package com.notessync.app.sync

import com.notessync.app.sync.crypto.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.AEADBadTagException

class PairingHandshakeTest {
    private val secret = "ab".repeat(32)
    private fun <T> sockets(block: (Socket, Socket, ExecutorService) -> T): T {
        val executor = Executors.newSingleThreadExecutor()
        try {
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
                Socket(InetAddress.getLoopbackAddress(), listener.localPort).use { client ->
                    listener.accept().use { server ->
                        client.soTimeout = 5_000; server.soTimeout = 5_000
                        return block(client, server, executor)
                    }
                }
            }
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(8, TimeUnit.SECONDS)) }
    }
    private class Recording(private val target: OutputStream) : OutputStream() {
        val record = ByteArrayOutputStream()
        override fun write(value: Int) { record.write(value); target.write(value) }
        override fun write(data: ByteArray, start: Int, count: Int) { record.write(data, start, count); target.write(data, start, count) }
        override fun flush() = target.flush()
    }
    private data class PairResult(val client: SessionCrypto, val server: SessionCrypto, val clientWire: ByteArray)
    private fun pair(): PairResult = sockets { client, server, executor ->
        val serving = executor.submit<SessionCrypto> { PairingHandshake.establish(server.getInputStream(), server.getOutputStream(), secret, false) }
        val output = Recording(client.getOutputStream())
        val clientCrypto = PairingHandshake.establish(client.getInputStream(), output, secret, true)
        PairResult(clientCrypto, serving.get(8, TimeUnit.SECONDS), output.record.toByteArray())
    }
    @Test fun `fresh authenticated ECDH connections have independent keys and do not transmit the pairing secret`() {
        val one = pair(); val two = pair(); val header = byteArrayOf(1, 0, 0, 0, 30); val plain = "secret".toByteArray()
        val first = one.client.encrypt(plain, header); val second = two.client.encrypt(plain, header)
        assertFalse(first.contentEquals(second)); assertArrayEquals(plain, one.server.decrypt(first, header))
        assertThrows(AEADBadTagException::class.java) { two.server.decrypt(first, header) }
        assertArrayEquals(plain, two.server.decrypt(second, header))
        assertFalse(one.clientWire.toString(Charsets.ISO_8859_1).contains(secret))
    }
    @Test fun `wrong PSK cannot authenticate or reach a data access callback`() = sockets { client, server, executor ->
        val accessed = AtomicBoolean(false)
        val serving = executor.submit<Boolean> {
            runCatching {
                PairingHandshake.establish(server.getInputStream(), server.getOutputStream(), secret, false)
                accessed.set(true)
            }.isFailure
        }
        assertTrue(runCatching { PairingHandshake.establish(client.getInputStream(), client.getOutputStream(), "cd".repeat(32), true) }.isFailure)
        client.close(); assertTrue(serving.get(8, TimeUnit.SECONDS)); assertFalse(accessed.get())
    }
    @Test fun `captured client hello and proof cannot authenticate a new connection`() {
        val old = pair()
        assertTrue(runCatching { PairingHandshake.establish(ByteArrayInputStream(old.clientWire), ByteArrayOutputStream(), secret, false) }.isFailure)
    }
    @Test fun `server authentication proof cannot be reflected as client proof`() = sockets { client, server, executor ->
        val serving = executor.submit<Boolean> { runCatching {
            PairingHandshake.establish(server.getInputStream(), server.getOutputStream(), secret, false)
        }.isFailure }
        val ec = java.security.KeyPairGenerator.getInstance("EC").apply { initialize(java.security.spec.ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val writer = DataOutputStream(client.getOutputStream()); val reader = DataInputStream(client.getInputStream())
        writer.write("NoteLS3\n".toByteArray()); writer.write(ByteArray(32)); writer.writeShort(ec.public.encoded.size); writer.write(ec.public.encoded); writer.flush()
        reader.readFully(ByteArray(8 + 32)); reader.readFully(ByteArray(reader.readUnsignedShort()))
        val serverProof = ByteArray(32).also(reader::readFully)
        writer.write(serverProof); writer.flush()
        assertTrue(serving.get(8, TimeUnit.SECONDS))
    }
    @Test fun `legacy envelope oversized public key and short manual secret fail closed`() {
        listOf("NoteLS2\n".toByteArray(), "NoteLS3\n".toByteArray() + ByteArray(32) + byteArrayOf(0x7f, 0xff.toByte())).forEach {
            assertTrue(runCatching { PairingHandshake.establish(ByteArrayInputStream(it), ByteArrayOutputStream(), secret, false) }.isFailure)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PairingHandshake.establish(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(), "ABCD2345", true)
        }
        repeat(16) { assertTrue(PairingSecret.valid(PairingSecret.generate())) }
    }
    @Test fun `even an authenticated malformed elliptic key cannot create a session`() = sockets { client, server, executor ->
        val serving = executor.submit<Throwable?> { runCatching {
            PairingHandshake.establish(server.getInputStream(), server.getOutputStream(), secret, false)
        }.exceptionOrNull() }
        val magic = "NoteLS3\n".toByteArray(); val nonce = ByteArray(32)
        val clientHello = nonce + byteArrayOf(0, 1, 0)
        val writer = DataOutputStream(client.getOutputStream()); val reader = DataInputStream(client.getInputStream())
        writer.write(magic + clientHello); writer.flush()
        reader.readFully(ByteArray(8))
        val serverNonce = ByteArray(32).also(reader::readFully); val length = reader.readUnsignedShort()
        val public = ByteArray(length).also(reader::readFully); reader.readFully(ByteArray(32))
        val serverHello = serverNonce + byteArrayOf((length ushr 8).toByte(), length.toByte()) + public
        val proof = Hkdf.hmac(PairingSecret.bytes(secret), "NoteLS3/client".toByteArray() + magic + clientHello + serverHello)
        writer.write(proof); writer.flush()
        assertTrue(serving.get(8, TimeUnit.SECONDS) is java.security.spec.InvalidKeySpecException)
    }
}
