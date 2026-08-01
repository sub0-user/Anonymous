package org.server.anonymous.business

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.AEADBadTagException

class MessageSessionTest {
    private val seedA = ByteArray(32) { 1 }
    private val seedB = ByteArray(32) { 2 }
    private val keysA = IdentityKeys.x25519KeyPairFromSeed(seedA)
    private val keysB = IdentityKeys.x25519KeyPairFromSeed(seedB)
    private val addressA = "a".repeat(56) + ".onion"
    private val addressB = "b".repeat(56) + ".onion"

    /** Two connected sockets on loopback (no Tor needed for the protocol layer). */
    private fun socketPair(): Pair<Socket, Socket> {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val initiator = Socket()
        initiator.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort))
        val responder = server.accept()
        server.close()
        return initiator to responder
    }

    /**
     * The responder handshake runs on a thread: each side blocks reading the other's HELLO,
     * so the two sides of one session can never be created sequentially on one thread.
     */
    private fun respondAsync(
        socket: Socket,
        keys: X25519KeyPair = keysB,
        address: String = addressB,
    ): CompletableFuture<MessageSession> =
        // Kotlin has no checked exceptions, so the future captures any failure directly.
        CompletableFuture.supplyAsync { MessageSession.respond(socket, keys, address) }

    private fun sessionPair(): Pair<MessageSession, MessageSession> {
        val (a, b) = socketPair()
        val bFuture = respondAsync(b)
        val sessionA = MessageSession.initiate(a, keysA, addressA)
        return sessionA to bFuture.get(5, TimeUnit.SECONDS)
    }

    @Test
    fun `initiator sends and responder receives a message end to end`() {
        val (sessionA, sessionB) = sessionPair()
        val receivedFuture = CompletableFuture.supplyAsync { sessionB.receiveMessage() }
        try {
            assertEquals(addressB, sessionA.peerAddress)
            assertEquals(addressA, sessionB.peerAddress)
            assertArrayEquals(keysB.publicKey, sessionA.peerPublicKey)
            assertArrayEquals(keysA.publicKey, sessionB.peerPublicKey)

            sessionA.sendMessage(WireProtocol.CONTENT_TEXT.toByte(), "hello from the onion".toByteArray())
            val received = receivedFuture.get(5, TimeUnit.SECONDS)
            assertEquals(WireProtocol.CONTENT_TEXT.toInt(), received.contentType.toInt())
            assertArrayEquals("hello from the onion".toByteArray(), received.body)
        } finally {
            sessionA.close()
            sessionB.close()
        }
    }

    @Test
    fun `roles swapped still work`() {
        val (a, b) = socketPair()
        try {
            val aFuture = respondAsync(a, keysA, addressA)
            val sessionB = MessageSession.initiate(b, keysB, addressB)
            val sessionA = aFuture.get(5, TimeUnit.SECONDS)
            val receivedFuture = CompletableFuture.supplyAsync { sessionA.receiveMessage() }
            sessionB.sendMessage(WireProtocol.CONTENT_TEXT.toByte(), "back at you".toByteArray())
            val received = receivedFuture.get(5, TimeUnit.SECONDS)
            assertArrayEquals("back at you".toByteArray(), received.body)
            sessionA.close()
            sessionB.close()
        } finally {
            runCatching { a.close() }
            runCatching { b.close() }
        }
    }

    @Test
    fun `two independent sessions both deliver`() {
        val (first, second) = sessionPair()
        val (third, fourth) = sessionPair()
        val secondReceive = CompletableFuture.supplyAsync { second.receiveMessage() }
        val fourthReceive = CompletableFuture.supplyAsync { fourth.receiveMessage() }
        try {
            first.sendMessage(WireProtocol.CONTENT_TEXT.toByte(), "same text".toByteArray())
            third.sendMessage(WireProtocol.CONTENT_TEXT.toByte(), "same text".toByteArray())
            assertArrayEquals(secondReceive.get(5, TimeUnit.SECONDS).body, fourthReceive.get(5, TimeUnit.SECONDS).body)
        } finally {
            first.close()
            second.close()
            third.close()
            fourth.close()
        }
    }

    @Test
    fun `garbage instead of a handshake is rejected`() {
        val (a, b) = socketPair()
        try {
            b.getOutputStream().write(byteArrayOf(0, 0, 0, 5, 1, 2, 3, 4, 5))
            b.getOutputStream().flush()
            assertThrows(IllegalStateException::class.java) {
                MessageSession.initiate(a, keysA, addressA)
            }
        } finally {
            runCatching { a.close() }
            runCatching { b.close() }
        }
    }

    @Test
    fun `oversized message is rejected before writing`() {
        val (sessionA, sessionB) = sessionPair()
        try {
            assertThrows(IllegalStateException::class.java) {
                sessionA.sendMessage(WireProtocol.CONTENT_TEXT.toByte(), ByteArray(WireProtocol.MAX_FRAME_SIZE))
            }
        } finally {
            sessionA.close()
            sessionB.close()
        }
    }

    /**
     * A raw B-side speaks the wire protocol manually (mirroring MessageSession's handshake
     * math) so we can corrupt the DATA frame on the wire and prove AEAD rejects it.
     */
    @Test
    fun `tampered data frame fails authentication`() {
        val (a, b) = socketPair()
        val bError = AtomicReference<Throwable?>(null)
        val bThread =
            Thread {
                try {
                    val bIn = DataInputStream(BufferedInputStream(b.getInputStream()))
                    val bOut = DataOutputStream(BufferedOutputStream(b.getOutputStream()))
                    val helloA = WireProtocol.readFrame(bIn)
                    val aPublic = helloA.payload.copyOfRange(2 + addressA.length, 2 + addressA.length + 32)
                    val aNonce = helloA.payload.copyOfRange(2 + addressA.length + 32, helloA.payload.size)
                    val bNonce = SessionCrypto.randomBytes(32)
                    val helloB =
                        byteArrayOf(WireProtocol.PROTOCOL_VERSION.toByte(), addressB.length.toByte()) +
                            addressB.toByteArray(Charsets.UTF_8) +
                            keysB.publicKey +
                            bNonce
                    WireProtocol.writeFrame(bOut, WireProtocol.TYPE_HELLO, helloB)

                    val shared = IdentityKeys.sharedSecret(keysB.privateKey, aPublic)
                    val salt = MessageDigest.getInstance("SHA-256").digest(aNonce + bNonce)
                    val bKeys = directionalKeys(SessionCrypto.sessionKeys(shared, salt, "anonymous/session/v1"), false)

                    val dataFrame = WireProtocol.readFrame(bIn)
                    val tampered =
                        dataFrame.payload.copyOf().also {
                            val index = SessionCrypto.NONCE_LENGTH + 5
                            it[index] = (it[index].toInt() xor 1).toByte()
                        }
                    val nonce = tampered.copyOfRange(0, SessionCrypto.NONCE_LENGTH)
                    val ciphertext = tampered.copyOfRange(SessionCrypto.NONCE_LENGTH, tampered.size)
                    try {
                        SessionCrypto.decrypt(bKeys.inbound, nonce, ciphertext, WireProtocol.AAD.toByteArray())
                        bError.set(AssertionError("tampered frame was accepted"))
                    } catch (expected: AEADBadTagException) {
                        // The tag must fail — this is the point of the test.
                    }
                    WireProtocol.writeFrame(bOut, WireProtocol.TYPE_ACK) // unblock the sender
                } catch (t: Throwable) {
                    bError.set(t)
                }
            }
        bThread.start()
        try {
            val sessionA = MessageSession.initiate(a, keysA, addressA)
            sessionA.sendMessage(WireProtocol.CONTENT_TEXT.toByte(), "integrity".toByteArray())
        } finally {
            bThread.join(5_000)
            assertNull(bError.get(), "B-side failed: ${bError.get()}")
            runCatching { a.close() }
            runCatching { b.close() }
        }
    }

    @Test
    fun `a wrong frame type in place of an ack is rejected`() {
        val (a, b) = socketPair()
        val bError = AtomicReference<Throwable?>(null)
        val bThread =
            Thread {
                try {
                    val bIn = DataInputStream(BufferedInputStream(b.getInputStream()))
                    val bOut = DataOutputStream(BufferedOutputStream(b.getOutputStream()))
                    val helloA = WireProtocol.readFrame(bIn)
                    val bNonce = SessionCrypto.randomBytes(32)
                    WireProtocol.writeFrame(
                        bOut,
                        WireProtocol.TYPE_HELLO,
                        byteArrayOf(WireProtocol.PROTOCOL_VERSION.toByte(), addressB.length.toByte()) +
                            addressB.toByteArray(Charsets.UTF_8) +
                            keysB.publicKey +
                            bNonce,
                    )
                    WireProtocol.readFrame(bIn) // A's DATA frame
                    WireProtocol.writeFrame(bOut, WireProtocol.TYPE_HELLO) // wrong "ack"
                } catch (t: Throwable) {
                    bError.set(t)
                }
            }
        bThread.start()
        try {
            val sessionA = MessageSession.initiate(a, keysA, addressA)
            val e =
                assertThrows(IllegalStateException::class.java) {
                    sessionA.sendMessage(WireProtocol.CONTENT_TEXT.toByte(), "waiting".toByteArray())
                }
            assertEquals("expected ACK, got type 1", e.message)
        } finally {
            bThread.join(5_000)
            assertNull(bError.get(), "B-side failed: ${bError.get()}")
            runCatching { a.close() }
            runCatching { b.close() }
        }
    }
}
