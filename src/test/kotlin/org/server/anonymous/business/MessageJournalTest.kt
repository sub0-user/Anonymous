package org.server.anonymous.business

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.server.anonymous.business.model.MessageDirection
import org.server.anonymous.business.model.MessageItem
import org.server.anonymous.business.model.MessageStatus
import org.server.anonymous.business.model.RoomMessageItem
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** The encrypted at-rest history layer (Phase A1): round-trips, restart, corruption, key binding. */
class MessageJournalTest {
    private val seedA = ByteArray(32) { (it + 1).toByte() }
    private val seedB = ByteArray(32) { (it + 9).toByte() }

    @Test
    fun `direct messages round-trip through the journal`() {
        val file = tempFile()
        val journal = journal(file, seedA)
        journal.append(7, item(1, "first", MessageStatus.SENT))
        journal.append(7, item(2, "second", MessageStatus.DELIVERED))

        val loaded = journal.load()
        assertEquals(2, loaded.size)
        assertEquals(7, loaded[0].first)
        assertEquals("first", loaded[0].second.body)
        assertEquals(MessageStatus.DELIVERED, loaded[1].second.status)
    }

    @Test
    fun `a fresh journal on the same file restores the history`() {
        val file = tempFile()
        journal(file, seedA).append(7, item(1, "hello", MessageStatus.DELIVERED))

        val restored = journal(file, seedA).load()
        assertEquals(1, restored.size)
        assertEquals("hello", restored.single().second.body)
    }

    @Test
    fun `room messages round-trip through the journal`() {
        val file = tempFile()
        val roomJournal = roomJournal(file, seedA)
        roomJournal.append(42, RoomMessageItem(1, 42, ByteArray(32) { 3 }, "room hello", "09:00", isOutgoing = false))

        val loaded = roomJournal.load()
        assertEquals(1, loaded.size)
        assertEquals(42, loaded.single().first)
        assertEquals("room hello", loaded.single().second.body)
    }

    @Test
    fun `clear removes only that conversation`() {
        val file = tempFile()
        val j = journal(file, seedA)
        j.append(1, item(1, "one", MessageStatus.DELIVERED))
        j.append(2, item(1, "two", MessageStatus.DELIVERED))
        j.append(1, item(2, "one again", MessageStatus.DELIVERED))

        j.clear(1)

        val loaded = j.load()
        assertEquals(listOf(2L), loaded.map { it.first })
        assertEquals(listOf("two"), loaded.map { it.second.body })
    }

    @Test
    fun `clearAll deletes the file`() {
        val file = tempFile()
        val j = journal(file, seedA)
        j.append(1, item(1, "bye", MessageStatus.DELIVERED))
        j.clearAll()
        assertFalse(Files.exists(file))
    }

    @Test
    fun `a truncated tail is ignored and the earlier records survive`() {
        val file = tempFile()
        val j = journal(file, seedA)
        j.append(1, item(1, "kept", MessageStatus.DELIVERED))
        j.append(1, item(2, "also kept", MessageStatus.DELIVERED))
        // Simulate a crash mid-write: a garbage length header with no payload.
        Files.write(file, byteArrayOf(0, 0, 0, 5), java.nio.file.StandardOpenOption.APPEND)

        val loaded = j.load()
        assertEquals(2, loaded.size)
        assertEquals(listOf("kept", "also kept"), loaded.map { it.second.body })
    }

    @Test
    fun `history is bound to the identity seed - a different key yields nothing`() {
        val file = tempFile()
        journal(file, seedA).append(1, item(1, "for me only", MessageStatus.DELIVERED))

        val loaded = journal(file, seedB).load()
        assertTrue(loaded.isEmpty())
    }

    @Test
    fun `plaintext never appears in the file`() {
        val file = tempFile()
        journal(file, seedA).append(1, item(1, "SECRET-BODY-ABC", MessageStatus.SENT))

        val raw = Files.readAllBytes(file).toString(Charsets.ISO_8859_1)
        assertFalse(raw.contains("SECRET-BODY-ABC"))
    }

    private fun journal(
        file: Path,
        seed: ByteArray,
    ): MessageJournal<MessageItem> =
        MessageJournal(
            file,
            { Identity(seed, Instant.now()) },
            HistoryCodec::encodeMessageItem,
            HistoryCodec::decodeMessageItem,
        )

    private fun roomJournal(
        file: Path,
        seed: ByteArray,
    ): MessageJournal<RoomMessageItem> =
        MessageJournal(
            file,
            { Identity(seed, Instant.now()) },
            HistoryCodec::encodeRoomItem,
            HistoryCodec::decodeRoomItem,
        )

    private fun item(
        id: Long,
        body: String,
        status: MessageStatus,
    ): MessageItem = MessageItem(id, MessageDirection.OUT, body, status, "09:00")

    private fun tempFile(): Path =
        Files.createTempDirectory("anon-journal").resolve("conversations.hist").also {
            it.parent.toFile().deleteOnExit()
        }
}
