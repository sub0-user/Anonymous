package org.server.anonymous.business

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RoomEnvelopeTest {
    private val roomId = 0x123456789ABCDEF0L

    private fun sampleMessage(): RoomEnvelope.RoomMessage {
        val nonce = SessionCrypto.randomNonce()
        val key = ByteArray(32) { 7 }
        val ciphertext =
            SessionCrypto.encrypt(key, nonce, "room hello".toByteArray(), RoomEnvelope.roomAad(roomId, 2))
        return RoomEnvelope.RoomMessage(roomId, 2, nonce, ciphertext)
    }

    @Test
    fun `room message roundtrip`() {
        val message = sampleMessage()
        val decoded = RoomEnvelope.decodeRoomMessage(RoomEnvelope.encodeRoomMessage(message))
        assertEquals(roomId, decoded.roomId)
        assertEquals(2, decoded.keyVersion)
        assertArrayEquals(message.nonce, decoded.nonce)
        assertArrayEquals(message.ciphertext, decoded.ciphertext)
    }

    @Test
    fun `decode rejects truncated bodies`() {
        assertThrows(IllegalStateException::class.java) {
            RoomEnvelope.decodeRoomMessage(ByteArray(10))
        }
    }

    @Test
    fun `aad binds room id and key version`() {
        val aad = RoomEnvelope.roomAad(roomId, 2)
        assertFalse(aad.contentEquals(RoomEnvelope.roomAad(roomId + 1, 2)))
        assertFalse(aad.contentEquals(RoomEnvelope.roomAad(roomId, 3)))
    }

    @Test
    fun `room id bytes roundtrip`() {
        assertEquals(roomId, RoomEnvelope.roomIdFromBytes(RoomEnvelope.roomIdToBytes(roomId)))
        assertEquals(0L, RoomEnvelope.roomIdFromBytes(RoomEnvelope.roomIdToBytes(0L)))
    }

    @Test
    fun `control frame roundtrip with payload`() {
        val payload = byteArrayOf(1, 2, 3)
        val decoded = RoomControls.decode(RoomControls.encode(RoomControls.OP_JOIN, roomId, payload))
        assertEquals(RoomControls.OP_JOIN, decoded.op)
        assertEquals(roomId, decoded.roomId)
        assertArrayEquals(payload, decoded.payload)
    }

    @Test
    fun `decode rejects unknown ops and short bodies`() {
        assertThrows(IllegalStateException::class.java) {
            RoomControls.decode(byteArrayOf(99, 1, 2, 3, 4, 5, 6, 7, 8))
        }
        assertThrows(IllegalStateException::class.java) {
            RoomControls.decode(ByteArray(3))
        }
    }

    @Test
    fun `member list roundtrip`() {
        val members =
            listOf(
                RoomControls.MemberEntry(ByteArray(32) { 1 }, "alice"),
                RoomControls.MemberEntry(ByteArray(32) { 2 }, "raven"),
            )
        val decoded = RoomControls.decodeMemberList(RoomControls.encodeMemberList(members))
        assertEquals(2, decoded.size)
        assertArrayEquals(ByteArray(32) { 1 }, decoded[0].publicKey)
        assertEquals("alice", decoded[0].name)
        assertEquals("raven", decoded[1].name)
    }

    @Test
    fun `key update and rename roundtrip`() {
        val wrapped = ByteArray(28) { 9 }
        val (version, key) = RoomControls.decodeKeyUpdate(RoomControls.encodeKeyUpdate(4, wrapped))
        assertEquals(4, version)
        assertArrayEquals(wrapped, key)

        val (pub, name) = RoomControls.decodeRename(RoomControls.encodeRename(ByteArray(32) { 5 }, "neo"))
        assertArrayEquals(ByteArray(32) { 5 }, pub)
        assertEquals("neo", name)
    }

    @Test
    fun `join payload encodes name and entry key`() {
        val key = EntryKey.generate()
        val private = RoomControls.decodeJoin(RoomControls.encodeJoin("neo", null))
        assertEquals("neo", private.name)
        assertEquals(null, private.entryKey)
        val public = RoomControls.decodeJoin(RoomControls.encodeJoin("neo", key))
        assertEquals("neo", public.name)
        assertEquals(key, public.entryKey)
    }
}
