package org.server.anonymous.business

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.server.anonymous.business.model.Contact
import org.server.anonymous.business.model.MessageDirection
import org.server.anonymous.business.model.MessageStatus
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class P2pMessageServiceTest {
    private val seedA = ByteArray(32) { 1 }
    private val seedB = ByteArray(32) { 2 }
    private val seedC = ByteArray(32) { 3 }
    private val keysB = IdentityKeys.x25519KeyPairFromSeed(seedB)
    private val keysC = IdentityKeys.x25519KeyPairFromSeed(seedC)
    private val addressA = "a".repeat(56) + ".onion"
    private val addressB = "b".repeat(56) + ".onion"

    /** A fake peer node: accepts connections, completes the session as the responder. */
    private class FakePeer(
        private val peerKeys: X25519KeyPair,
        private val peerAddress: String,
    ) {
        val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        val received = CopyOnWriteArrayList<String>()

        @Suppress("SwallowedException") // a dropped test connection is expected
        fun start() {
            Thread {
                while (!server.isClosed) {
                    try {
                        val socket = server.accept()
                        Thread {
                            try {
                                val session = MessageSession.respond(socket, peerKeys, peerAddress)
                                val msg = session.receiveMessage()
                                received += msg.body.toString(Charsets.UTF_8)
                            } catch (t: Throwable) {
                                // dropped connection
                            }
                        }.apply {
                            isDaemon = true
                            start()
                        }
                    } catch (t: Throwable) {
                        break
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }
        }

        fun close() {
            server.close()
        }
    }

    private fun await(
        timeoutMs: Long = 10_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        error("condition not met within ${timeoutMs}ms")
    }

    private fun service(
        contacts: ContactBook,
        listener: ServerSocket? = null,
        rateLimit: Int = 30,
        socketFactory: (Int, String, Int) -> Socket = { _, _, _ -> Socket(InetAddress.getLoopbackAddress(), 1) },
    ): P2pMessageService =
        P2pMessageService(
            contacts,
            { NodeStatus.Online(addressA, socksPort = 9050) },
            { listener },
            { Identity(seedA, Instant.now()) },
            socketFactory = socketFactory,
            rateLimitPerMinute = rateLimit,
            retryScanMillis = 100,
            retryBackoffBaseMillis = 100,
        ).also { if (listener != null) it.startListener() }

    private fun contactFor(book: ContactBook): Contact {
        val result = book.addContact("peer", addressB)
        return (result as OpResult.Success).value
    }

    private fun peerSocketFactory(peer: FakePeer): (Int, String, Int) -> Socket =
        { _, _, _ -> Socket(InetAddress.getLoopbackAddress(), peer.server.localPort) }

    /** A peer-initiated session connected straight to the service's listener. */
    private fun peerSession(listener: ServerSocket): MessageSession =
        MessageSession.initiate(Socket(InetAddress.getLoopbackAddress(), listener.localPort), keysB, addressB)

    @Test
    fun `send delivers through the peer and marks the message delivered`() {
        val book = ContactBook()
        val contact = contactFor(book)
        val peer = FakePeer(keysB, addressB).also { it.start() }
        try {
            val svc = service(book, socketFactory = peerSocketFactory(peer))
            val received = mutableListOf<org.server.anonymous.business.model.MessageItem>()
            svc.addMessageListener { received += it }

            val result = svc.send(contact.id, "hello from the onion")
            assertTrue(result is OpResult.Success)
            await { peer.received.contains("hello from the onion") }
            await { svc.messagesFor(contact.id).last().status == MessageStatus.DELIVERED }

            assertTrue(received.any { it.status == MessageStatus.DELIVERED })
            assertEquals("hello from the onion", svc.messagesFor(contact.id).single().body)
        } finally {
            peer.close()
        }
    }

    @Test
    fun `undeliverable message stays queued instead of failing`() {
        val book = ContactBook()
        val contact = contactFor(book)
        val svc = service(book) // default factory hits a closed loopback port
        svc.send(contact.id, "to nobody")
        // Connectivity problems never fail the message — the outbox keeps it queued for retry.
        await { svc.messagesFor(contact.id).single().status == MessageStatus.SENT }
        Thread.sleep(300)
        assertEquals(MessageStatus.SENT, svc.messagesFor(contact.id).single().status)
    }

    @Test
    fun `offline message is delivered once the peer comes back`() {
        val book = ContactBook()
        val contact = contactFor(book)
        val peer = FakePeer(keysB, addressB).also { it.start() }
        try {
            var peerOnline = false
            val factory: (Int, String, Int) -> Socket = { _, _, _ ->
                if (peerOnline) {
                    Socket(InetAddress.getLoopbackAddress(), peer.server.localPort)
                } else {
                    Socket(InetAddress.getLoopbackAddress(), 1)
                }
            }
            val svc = service(book, socketFactory = factory)
            svc.send(contact.id, "when you are back")
            // While the peer is offline the message is queued, not failed.
            await { svc.messagesFor(contact.id).single().status == MessageStatus.SENT }
            peerOnline = true
            // The next outbox scan delivers it and the ACK flips the status.
            await { svc.messagesFor(contact.id).single().status == MessageStatus.DELIVERED }
            assertTrue(peer.received.contains("when you are back"))
        } finally {
            peer.close()
        }
    }

    @Test
    fun `inbound from a known contact is stored and notified`() {
        val book = ContactBook()
        val contact = contactFor(book)
        val listener = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        val svc = service(book, listener)
        try {
            val notified = mutableListOf<org.server.anonymous.business.model.MessageItem>()
            svc.addMessageListener { notified += it }
            val peer = peerSession(listener)
            try {
                peer.sendMessage(WireProtocol.CONTENT_TEXT.toByte(), "inbound hello".toByteArray())
            } finally {
                peer.close()
            }
            await { svc.messagesFor(contact.id).any { it.direction == MessageDirection.IN } }
            val message = svc.messagesFor(contact.id).single()
            assertEquals("inbound hello", message.body)
            assertEquals(MessageStatus.DELIVERED, message.status)
            await { notified.any { it.body == "inbound hello" } }
        } finally {
            listener.close()
        }
    }

    @Test
    fun `inbound from an unknown address becomes a request`() {
        val book = ContactBook()
        val listener = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        val svc = service(book, listener)
        try {
            val peer = peerSession(listener)
            try {
                peer.sendMessage(WireProtocol.CONTENT_TEXT.toByte(), "are you there?".toByteArray())
            } finally {
                peer.close()
            }
            await { book.incomingRequests().isNotEmpty() }
            val request = book.incomingRequests().single()
            assertEquals(addressB, request.address.value)
            assertEquals("are you there?", request.preview)
        } finally {
            listener.close()
        }
    }

    @Test
    fun `inbound from a blocked address is dropped and the sender fails fast`() {
        val book = ContactBook()
        val contact = contactFor(book)
        book.block(addressB)
        val listener = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        val svc = service(book, listener)
        try {
            val peer = peerSession(listener)
            try {
                peer.sendMessage(WireProtocol.CONTENT_TEXT.toByte(), "spam".toByteArray())
                error("expected the dropped connection to fail")
            } catch (expected: IOException) {
                // The receiver closed without acknowledging — the sender fails fast.
            } finally {
                peer.close()
            }
            await { svc.messagesFor(contact.id).isEmpty() && book.incomingRequests().isEmpty() }
        } finally {
            listener.close()
        }
    }

    @Test
    fun `peer key change on outbound marks the message failed`() {
        val book = ContactBook()
        val contact = contactFor(book)
        book.bindPeerKey(contact.id, keysB.publicKey)
        val imposter = FakePeer(keysC, addressB).also { it.start() } // different key, same claimed address
        try {
            val svc = service(book, socketFactory = peerSocketFactory(imposter))
            svc.send(contact.id, "to an imposter")
            await { svc.messagesFor(contact.id).single().status == MessageStatus.FAILED }
            assertEquals(MessageStatus.FAILED, svc.messagesFor(contact.id).single().status)
        } finally {
            imposter.close()
        }
    }

    @Test
    fun `rate limit drops excess inbound traffic per peer`() {
        val book = ContactBook()
        val listener = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        val svc = service(book, listener, rateLimit = 2)
        try {
            val results = mutableListOf<Boolean>()
            repeat(4) { i ->
                val peer = peerSession(listener)
                try {
                    peer.sendMessage(WireProtocol.CONTENT_TEXT.toByte(), "msg $i".toByteArray())
                    results += true
                } catch (expected: IOException) {
                    results += false
                } finally {
                    peer.close()
                }
            }
            // Two messages are accepted and acknowledged; the excess two are dropped.
            assertEquals(listOf(true, true, false, false), results)
            // Both accepted messages collapse into one request for the same address.
            assertEquals(1, book.incomingRequests().size)
        } finally {
            listener.close()
        }
    }

    @Test
    fun `probePeerKey binds and returns the peer key`() {
        val book = ContactBook()
        val contact = contactFor(book)
        assertNull(book.peerPublicKeyOf(contact.id))
        val peer = FakePeer(keysB, addressB).also { it.start() }
        try {
            val svc = service(book, socketFactory = peerSocketFactory(peer))
            val result = svc.probePeerKey(contact)
            assertTrue(result is OpResult.Success)
            assertArrayEquals(keysB.publicKey, (result as OpResult.Success).value)
            assertArrayEquals(keysB.publicKey, book.peerPublicKeyOf(contact.id))
        } finally {
            peer.close()
        }
    }

    @Test
    fun `probePeerKey fails cleanly when the peer is unreachable`() {
        val book = ContactBook()
        val contact = contactFor(book)
        val svc = service(book)
        assertTrue(svc.probePeerKey(contact) is OpResult.Failure)
        assertNull(book.peerPublicKeyOf(contact.id))
    }

    @Test
    fun `probePeerKey refuses a blocked contact`() {
        val book = ContactBook()
        val contact = contactFor(book)
        book.block(addressB)
        val svc = service(book)
        assertTrue(svc.probePeerKey(contact) is OpResult.Failure)
    }
}
