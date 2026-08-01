package org.server.anonymous.business

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import javax.crypto.AEADBadTagException

class RoomKeyWrapTest {
    private val founder = IdentityKeys.x25519KeyPairFromSeed(ByteArray(32) { 1 })
    private val member = IdentityKeys.x25519KeyPairFromSeed(ByteArray(32) { 2 })
    private val roomId = 0x1122334455667788L
    private val roomKey = RoomKeyWrap.newRoomKey()

    @Test
    fun `wrap and unwrap roundtrip`() {
        val wrapKey = RoomKeyWrap.wrapKey(founder.privateKey, member.publicKey, roomId)
        val wrapped = RoomKeyWrap.wrap(roomKey, wrapKey, roomId)
        assertArrayEquals(roomKey, RoomKeyWrap.unwrap(wrapped, wrapKey, roomId))
    }

    @Test
    fun `the member can derive the same wrap key from their half`() {
        val founderWrap = RoomKeyWrap.wrapKey(founder.privateKey, member.publicKey, roomId)
        val memberWrap = RoomKeyWrap.wrapKey(member.privateKey, founder.publicKey, roomId)
        assertArrayEquals(founderWrap, memberWrap)
    }

    @Test
    fun `tampered wrap is rejected`() {
        val wrapKey = RoomKeyWrap.wrapKey(founder.privateKey, member.publicKey, roomId)
        val wrapped = RoomKeyWrap.wrap(roomKey, wrapKey, roomId)
        val tampered = wrapped.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertThrows(AEADBadTagException::class.java) {
            RoomKeyWrap.unwrap(tampered, wrapKey, roomId)
        }
    }

    @Test
    fun `a different wrap key cannot unwrap`() {
        val wrapKey = RoomKeyWrap.wrapKey(founder.privateKey, member.publicKey, roomId)
        val otherMember = IdentityKeys.x25519KeyPairFromSeed(ByteArray(32) { 3 })
        val otherWrap = RoomKeyWrap.wrapKey(founder.privateKey, otherMember.publicKey, roomId)
        val wrapped = RoomKeyWrap.wrap(roomKey, wrapKey, roomId)
        assertThrows(AEADBadTagException::class.java) {
            RoomKeyWrap.unwrap(wrapped, otherWrap, roomId)
        }
    }

    @Test
    fun `wrap is bound to the room id`() {
        val wrapKey = RoomKeyWrap.wrapKey(founder.privateKey, member.publicKey, roomId)
        val wrapped = RoomKeyWrap.wrap(roomKey, wrapKey, roomId)
        assertThrows(AEADBadTagException::class.java) {
            RoomKeyWrap.unwrap(wrapped, wrapKey, roomId + 1)
        }
    }

    @Test
    fun `rotation produces a fresh key that old wraps cannot reveal`() {
        val wrapKey = RoomKeyWrap.wrapKey(founder.privateKey, member.publicKey, roomId)
        val fresh = RoomKeyWrap.newRoomKey()
        assertFalse(fresh.contentEquals(roomKey))
        assertEquals(RoomKeyWrap.ROOM_KEY_LENGTH, fresh.size)
        assertArrayEquals(fresh, RoomKeyWrap.unwrap(RoomKeyWrap.wrap(fresh, wrapKey, roomId), wrapKey, roomId))
    }
}
