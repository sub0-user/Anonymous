package org.server.anonymous.business

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.server.anonymous.business.model.MessageDirection
import org.server.anonymous.business.model.MessageItem
import org.server.anonymous.business.model.MessageStatus
import org.server.anonymous.business.model.RoomMember
import org.server.anonymous.business.model.RoomMessageItem
import org.server.anonymous.business.model.RoomRecord
import org.server.anonymous.business.model.RoomType
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Phase A1 end-to-end proof at the service level: messages survive an app restart because
 * they are journaled at rest. The 1:1 service and the room messenger are re-created on the
 * same files the way AppGraph does on launch, and the history comes back.
 */
class MessagePersistenceTest {
    private val seedA = ByteArray(32) { (it + 1).toByte() }
    private val seedB = ByteArray(32) { (it + 9).toByte() }
    private val addressA = "a".repeat(56) + ".onion"
    private val addressB = "b".repeat(56) + ".onion"

    @Test
    fun `direct messages and their final status survive a restart`() {
        val dir = tempDir()
        val file = dir.resolve("conversations.hist")
        val book = ContactBook()
        val contact = (book.addContact("peer", addressB) as OpResult.Success).value

        val first = p2pService(book, journal(file))
        first.send(contact.id, "survive the restart")
        // Connectivity failure leaves the message queued (SENT) — the outbox owns delivery.
        await { first.messagesFor(contact.id).single().status == MessageStatus.SENT }
        // The status append lands right after the in-memory update — wait for it on disk.
        await { journal(file).load().any { it.second.body == "survive the restart" } }
        first.stop()

        val second = p2pService(book, journal(file))
        val restored = second.messagesFor(contact.id)
        assertEquals(1, restored.size)
        assertEquals("survive the restart", restored.single().body)
        assertEquals(MessageDirection.OUT, restored.single().direction)
        assertEquals(MessageStatus.SENT, restored.single().status)
        second.stop()
    }

    @Test
    fun `inbound direct messages survive a restart`() {
        val dir = tempDir()
        val file = dir.resolve("conversations.hist")
        val book = ContactBook()
        val contact = (book.addContact("peer", addressB) as OpResult.Success).value
        val listener = ServerSocket(0)
        try {
            val first = p2pService(book, journal(file), listener = listener)
            first.startListener()
            val peer = Socket(InetAddress.getLoopbackAddress(), listener.localPort)
            val session = MessageSession.initiate(peer, IdentityKeys.x25519KeyPairFromSeed(seedB), addressB)
            session.sendMessage(WireProtocol.CONTENT_TEXT.toByte(), "inbound survives".toByteArray())
            session.close()
            await { first.messagesFor(contact.id).any { it.direction == MessageDirection.IN } }
            // The append lands right after the in-memory update — wait for it on disk.
            await { journal(file).load().any { it.second.body == "inbound survives" } }
            first.stop()

            val second = p2pService(book, journal(file))
            val restored = second.messagesFor(contact.id)
            assertTrue(restored.any { it.direction == MessageDirection.IN && it.body == "inbound survives" })
            second.stop()
        } finally {
            listener.close()
        }
    }

    @Test
    fun `room messages survive a restart`() {
        val dir = tempDir()
        val store = RoomStore(dir.resolve("rooms"))
        store.save(roomRecord())
        val file = dir.resolve("rooms").resolve("messages.hist")
        val roomJournal = roomJournal(file)

        val first = roomMessenger(store, roomJournal)
        val sent = first.sendMessage(1, "room text survives")
        assertTrue(sent is OpResult.Success)

        // Restart: a fresh messenger on the same store+journal restores the room history.
        val second = roomMessenger(store, roomJournal)
        assertEquals(listOf("room text survives"), second.messagesFor(1).map { it.body })

        // Clear history removes it at rest too.
        second.clearHistory(1)
        val third = roomMessenger(store, roomJournal)
        assertTrue(third.messagesFor(1).isEmpty())
    }

    private fun p2pService(
        book: ContactBook,
        history: MessageJournal<MessageItem>,
        listener: ServerSocket? = null,
        // Default factory fails fast (connect refused), so delivery lands on FAILED without Tor.
        socketFactory: (Int, String, Int) -> Socket = { _, _, _ -> Socket(InetAddress.getLoopbackAddress(), 1) },
    ): P2pMessageService =
        P2pMessageService(
            book,
            { NodeStatus.Online(addressA, socksPort = 9050) },
            { listener },
            { Identity(seedA, Instant.now()) },
            socketFactory = socketFactory,
            messageHistory = history,
        )

    private fun roomMessenger(
        store: RoomStore,
        history: MessageJournal<RoomMessageItem>,
    ): RoomMessenger =
        RoomMessenger(
            store,
            { Identity(seedA, Instant.now()) },
            sender = { _, _, _, _ -> true },
            roomHistory = history,
        )

    private fun roomRecord(): RoomRecord =
        RoomRecord(
            id = 1,
            name = "persist-room",
            type = RoomType.PRIVATE,
            isFounder = true,
            founderAddress = null,
            founderPublicKey = null,
            serviceSeed = ByteArray(32) { 5 },
            serviceAddress = "c".repeat(56) + ".onion",
            roomKey = ByteArray(32) { 7 },
            keyVersion = 1,
            entryKey = null,
            myName = "me",
            members = listOf(RoomMember(ByteArray(32) { 3 }, "me", address = addressA)),
        )

    private fun journal(file: Path): MessageJournal<MessageItem> =
        MessageJournal(
            file,
            { Identity(seedA, Instant.now()) },
            HistoryCodec::encodeMessageItem,
            HistoryCodec::decodeMessageItem,
        )

    private fun roomJournal(file: Path): MessageJournal<RoomMessageItem> =
        MessageJournal(
            file,
            { Identity(seedA, Instant.now()) },
            HistoryCodec::encodeRoomItem,
            HistoryCodec::decodeRoomItem,
        )

    private fun tempDir(): Path = Files.createTempDirectory("anon-persist").also { it.toFile().deleteOnExit() }

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
}
