package org.server.anonymous.business

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class InviteCodecTest {
    private val roomId = 0x5EADBEEFCAFEBABEL
    private val service = "a".repeat(56) + ".onion"
    private val founder = "b".repeat(56) + ".onion"
    private val founderPublicKey = ByteArray(32) { 4 }
    private val authPrivate = ByteArray(32) { 11 }
    private val wrappedKey = SessionCrypto.randomNonce() + ByteArray(28) { 12 }

    @Test
    fun `private invite roundtrip without expiry`() {
        val invite = PrivateRoomInvite(roomId, service, founder, authPrivate, wrappedKey, founderPublicKey)
        val decoded = InviteCodec.decode(InviteCodec.encode(invite))
        assertEquals(PrivateRoomInvite::class, decoded::class)
        decoded as PrivateRoomInvite
        assertEquals(roomId, decoded.roomId)
        assertEquals(service, decoded.serviceAddress)
        assertEquals(founder, decoded.founderAddress)
        assertArrayEquals(authPrivate, decoded.clientAuthPrivate)
        assertArrayEquals(wrappedKey, decoded.wrappedRoomKey)
        assertArrayEquals(founderPublicKey, decoded.founderPublicKey)
        assertEquals(null, decoded.expiryEpochSeconds)
    }

    @Test
    fun `private invite roundtrip with expiry`() {
        val invite =
            PrivateRoomInvite(
                roomId,
                service,
                founder,
                authPrivate,
                wrappedKey,
                founderPublicKey,
                1_752_000_000L,
            )
        val decoded = InviteCodec.decode(InviteCodec.encode(invite)) as PrivateRoomInvite
        assertEquals(1_752_000_000L, decoded.expiryEpochSeconds)
    }

    @Test
    fun `public invite roundtrip`() {
        val entryKey = EntryKey.generate()
        val invite = PublicRoomInvite(roomId, service, founder, entryKey, founderPublicKey)
        val decoded = InviteCodec.decode(InviteCodec.encode(invite))
        assertEquals(PublicRoomInvite::class, decoded::class)
        decoded as PublicRoomInvite
        assertEquals(roomId, decoded.roomId)
        assertEquals(service, decoded.serviceAddress)
        assertEquals(entryKey, decoded.entryKey)
        assertEquals(founder, decoded.founderAddress)
        assertArrayEquals(founderPublicKey, decoded.founderPublicKey)
    }

    @Test
    fun `tampered invites are rejected`() {
        val privateText =
            InviteCodec.encode(PrivateRoomInvite(roomId, service, founder, authPrivate, wrappedKey, founderPublicKey))
        val publicText =
            InviteCodec.encode(PublicRoomInvite(roomId, service, founder, EntryKey.generate(), founderPublicKey))
        assertThrows(IllegalStateException::class.java) { InviteCodec.decode(privateText + "x") }
        assertThrows(IllegalStateException::class.java) { InviteCodec.decode(publicText + "x") }
        assertThrows(IllegalStateException::class.java) { InviteCodec.decode("not-an-invite") }
        assertThrows(IllegalStateException::class.java) { InviteCodec.decode("") }
    }

    @Test
    fun `invalid addresses are rejected`() {
        val bad = PrivateRoomInvite(roomId, "not-an-onion", founder, authPrivate, wrappedKey, founderPublicKey)
        assertThrows(IllegalStateException::class.java) { InviteCodec.encode(bad) }
    }

    @Test
    fun `bad base64 and bad entry keys are rejected`() {
        val badKey = PrivateRoomInvite(roomId, service, founder, ByteArray(31), wrappedKey, founderPublicKey)
        assertThrows(IllegalStateException::class.java) { InviteCodec.decode(InviteCodec.encode(badKey)) }
        val badEntry = PublicRoomInvite(roomId, service, founder, "NOTAKEY", founderPublicKey)
        assertThrows(IllegalStateException::class.java) { InviteCodec.decode(InviteCodec.encode(badEntry)) }
    }
}
