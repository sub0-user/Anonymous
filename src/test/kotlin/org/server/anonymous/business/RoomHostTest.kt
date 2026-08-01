package org.server.anonymous.business

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.server.anonymous.business.model.MemberStatus
import org.server.anonymous.business.model.RoomType
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Base64

class RoomHostTest {
    private val address = "a".repeat(56) + ".onion"
    private val identity = Identity(ByteArray(32) { 3 }, Instant.now())
    private val myKeys = IdentityKeys.x25519KeyPairFromSeed(identity.seed)

    private class FakeTorControl : TorControl {
        val addedBlobs = mutableListOf<List<String>>()
        var deletedCount = 0

        override fun connect(
            host: String,
            port: Int,
        ) = Unit

        override fun authenticate(cookie: ByteArray) = Unit

        override fun bootstrapProgress(): Int? = 100

        override fun addOnionService(
            seed: ByteArray,
            virtualPort: Int,
            targetHost: String,
            targetPort: Int,
        ): String {
            addedBlobs.add(emptyList())
            return "a".repeat(56) + ".onion"
        }

        override fun addOnionServiceWithClientAuth(
            seed: ByteArray,
            virtualPort: Int,
            targetHost: String,
            targetPort: Int,
            clientAuthBlobs: List<String>,
        ): String {
            addedBlobs.add(clientAuthBlobs)
            return "a".repeat(56) + ".onion"
        }

        override fun deleteOnionService(svcAddress: String) {
            deletedCount++
        }

        override fun signalHup() = Unit

        override fun close() = Unit
    }

    private class FakeSender {
        val sent = mutableListOf<Pair<String, ByteArray>>()
        val fn: (String, ByteArray?, Byte, ByteArray) -> Boolean = { to, _, _, body ->
            sent += to to body
            true
        }
    }

    private class Fixture(
        val host: RoomHost,
        val tor: FakeTorControl,
        val sender: FakeSender,
        val storeDir: Path,
    ) {
        fun store(): RoomStore = RoomStore(storeDir)
    }

    private fun newDir(): Path = Files.createTempDirectory("anonymous-room-host").also { it.toFile().deleteOnExit() }

    private fun fixture(): Fixture {
        val tor = FakeTorControl()
        val sender = FakeSender()
        val storeDir = newDir()
        val host =
            RoomHost(
                RoomStore(storeDir),
                { NodeStatus.Online(address, 40_000) },
                { tor },
                { identity },
                sender.fn,
            )
        return Fixture(host, tor, sender, storeDir)
    }

    private fun createdRoom(
        fx: Fixture,
        name: String = "dev den",
        type: RoomType = RoomType.PRIVATE,
        myName: String = "raven",
    ) = (fx.host.createRoom(name, type, myName) as OpResult.Success).value

    private fun memberPair(seed: Byte = 7): X25519KeyPair = IdentityKeys.x25519KeyPairFromSeed(ByteArray(32) { seed })

    private fun memberAddress(): String = "b".repeat(56) + ".onion"

    /** True when a ClientAuth blob's private half belongs to the given member key. */
    private fun isAuthFor(
        blob: String,
        memberKey: ByteArray,
    ): Boolean {
        val decoded = Base64.getDecoder().decode(blob)
        val privateHalf = decoded.copyOfRange(32, 64)
        return privateHalf.contentEquals(memberKey)
    }

    @Test
    fun `create private room persists and hosts the service`() {
        val fx = fixture()
        val room = createdRoom(fx)
        assertTrue(room.isFounder)
        assertNull(room.entryKey)
        assertEquals(1, room.members.size)
        assertArrayEquals(myKeys.publicKey, room.members[0].publicKey)
        assertEquals(1, fx.tor.addedBlobs.size)
        assertTrue(fx.tor.addedBlobs[0].isEmpty())
    }

    @Test
    fun `create public room has an entry key and no client auth`() {
        val fx = fixture()
        val room = createdRoom(fx, type = RoomType.PUBLIC)
        assertNotNull(room.entryKey)
        assertTrue(EntryKey.isValid(room.entryKey!!))
        assertTrue(fx.tor.addedBlobs[0].isEmpty())
    }

    @Test
    fun `create room while offline fails`() {
        val tor = FakeTorControl()
        val storeDir = newDir()
        val offline =
            RoomHost(
                RoomStore(storeDir),
                { NodeStatus.Offline("off") },
                { tor },
                { identity },
                FakeSender().fn,
            )
        assertTrue(offline.createRoom("x", RoomType.PRIVATE, "me") is OpResult.Failure)
    }

    @Test
    fun `private invite carries auth key and a key that unwraps the room key`() {
        val fx = fixture()
        val room = createdRoom(fx)
        val member = memberPair()
        val result = fx.host.createInvite(room.id, memberAddress(), member.publicKey, "neo", null)
        assertTrue(result is OpResult.Success)
        val invite = InviteCodec.decode((result as OpResult.Success).value) as PrivateRoomInvite
        // The invite's private half is what gets registered on the service (via its public half).
        val invitePublic = IdentityKeys.x25519PublicKeyFromScalar(invite.clientAuthPrivate)
        val wrapKey = RoomKeyWrap.wrapKey(myKeys.privateKey, member.publicKey, room.id)
        assertArrayEquals(room.roomKey, RoomKeyWrap.unwrap(invite.wrappedRoomKey, wrapKey, room.id))

        val saved = fx.store().loadAll().first()
        assertEquals(MemberStatus.INVITED, saved.members[1].status)
        // The new member's client-auth blob is registered on the service.
        val blob =
            fx.tor
                .addedBlobs
                .last()
                .single()
        val decoded = Base64.getDecoder().decode(blob)
        assertArrayEquals(invitePublic, decoded.copyOfRange(0, 32))
        assertArrayEquals(invite.clientAuthPrivate, decoded.copyOfRange(32, 64))
    }

    @Test
    fun `public invite shares the entry key`() {
        val fx = fixture()
        val room = createdRoom(fx, type = RoomType.PUBLIC)
        val result = fx.host.createInvite(room.id, memberAddress(), ByteArray(32) { 9 }, "neo", null)
        assertTrue(result is OpResult.Success)
        val invite = InviteCodec.decode((result as OpResult.Success).value) as PublicRoomInvite
        assertEquals(room.entryKey, invite.entryKey)
    }

    @Test
    fun `inviting an existing member fails`() {
        val fx = fixture()
        val room = createdRoom(fx)
        val result = fx.host.createInvite(room.id, address, myKeys.publicKey, "dupe", null)
        assertTrue(result is OpResult.Failure)
    }

    @Test
    fun `private join promotes the member and delivers key and list`() {
        val fx = fixture()
        val room = createdRoom(fx)
        val member = memberPair()
        fx.host.createInvite(room.id, memberAddress(), member.publicKey, "neo", null)
        fx.sender.sent.clear()

        assertTrue(fx.host.handleJoin(room.id, member.publicKey, memberAddress(), "neo", null))

        val stored = fx.store().loadAll().first()
        assertEquals(MemberStatus.MEMBER, stored.members.first { it.publicKey.contentEquals(member.publicKey) }.status)
        val ops = fx.sender.sent.map { RoomControls.decode(it.second).op }
        assertTrue(RoomControls.OP_KEY_UPDATE in ops)
        assertTrue(RoomControls.OP_MEMBER_LIST in ops)
    }

    @Test
    fun `private join with an unknown key is rejected`() {
        val fx = fixture()
        val room = createdRoom(fx)
        assertFalse(fx.host.handleJoin(room.id, ByteArray(32) { 99 }, memberAddress(), "ghost", null))
        assertTrue(fx.sender.sent.isEmpty())
    }

    @Test
    fun `private join with an expired invite is rejected and dropped`() {
        val fx = fixture()
        val room = createdRoom(fx)
        val member = memberPair()
        val past = Instant.now().epochSecond - 1000
        fx.host.createInvite(room.id, memberAddress(), member.publicKey, "neo", past)
        val deletedBefore = fx.tor.deletedCount

        assertFalse(fx.host.handleJoin(room.id, member.publicKey, memberAddress(), "neo", null))
        val stored = fx.store().loadAll().first()
        assertTrue(stored.members.none { it.publicKey.contentEquals(member.publicKey) })
        assertTrue(fx.tor.deletedCount > deletedBefore) // auth entry revoked
    }

    @Test
    fun `public join with the wrong entry key is rejected`() {
        val fx = fixture()
        val room = createdRoom(fx, type = RoomType.PUBLIC)
        assertFalse(fx.host.handleJoin(room.id, ByteArray(32) { 9 }, memberAddress(), "neo", "WRONGKEY"))
        assertTrue(fx.sender.sent.isEmpty())
    }

    @Test
    fun `public join adds the member with a key that unwraps the room key`() {
        val fx = fixture()
        val room = createdRoom(fx, type = RoomType.PUBLIC)
        val member = memberPair()

        assertTrue(fx.host.handleJoin(room.id, member.publicKey, memberAddress(), "neo", room.entryKey))
        val stored = fx.store().loadAll().first()
        val joined = stored.members.first { it.publicKey.contentEquals(member.publicKey) }
        assertEquals(MemberStatus.MEMBER, joined.status)
        val wrapKey = RoomKeyWrap.wrapKey(myKeys.privateKey, member.publicKey, room.id)
        assertArrayEquals(stored.roomKey, RoomKeyWrap.unwrap(joined.wrappedRoomKey!!, wrapKey, room.id))
    }

    @Test
    fun `kick rotates the key and locks out the kicked member`() {
        val fx = fixture()
        val room = createdRoom(fx)
        val member = memberPair()
        val inviteResult = fx.host.createInvite(room.id, memberAddress(), member.publicKey, "neo", null)
        val invite = InviteCodec.decode((inviteResult as OpResult.Success).value) as PrivateRoomInvite
        assertTrue(fx.host.handleJoin(room.id, member.publicKey, memberAddress(), "neo", null))
        val oldWrapKey = RoomKeyWrap.wrapKey(myKeys.privateKey, member.publicKey, room.id)
        val blobsBefore = fx.tor.addedBlobs.size
        fx.sender.sent.clear()

        assertTrue(fx.host.kickMember(room.id, member.publicKey))

        val stored = fx.store().loadAll().first()
        assertEquals(room.keyVersion + 1, stored.keyVersion)
        val kicked = stored.members.first { it.publicKey.contentEquals(member.publicKey) }
        assertEquals(MemberStatus.KICKED, kicked.status)
        assertNull(kicked.wrappedRoomKey)
        // The old wrapped key from the invite no longer yields the live room key.
        assertFalse(RoomKeyWrap.unwrap(invite.wrappedRoomKey, oldWrapKey, room.id).contentEquals(stored.roomKey))
        // The remaining member's wrap is fresh and still valid.
        val myWrap = stored.members.first { it.publicKey.contentEquals(myKeys.publicKey) }.wrappedRoomKey!!
        val myWrapKey = RoomKeyWrap.wrapKey(myKeys.privateKey, myKeys.publicKey, room.id)
        assertArrayEquals(stored.roomKey, RoomKeyWrap.unwrap(myWrap, myWrapKey, room.id))
        // The kicked member gets a KICK notice and their auth entry is revoked.
        val ops = fx.sender.sent.map { RoomControls.decode(it.second).op }
        assertTrue(RoomControls.OP_KICK in ops)
        assertTrue(fx.tor.addedBlobs.size > blobsBefore)
        val kickedBlobGone =
            fx.tor
                .addedBlobs
                .last()
                .none { blob -> isAuthFor(blob, member.publicKey) }
        assertTrue(kickedBlobGone)
    }

    @Test
    fun `rename updates the name and broadcasts`() {
        val fx = fixture()
        val room = createdRoom(fx)
        val member = memberPair()
        fx.host.createInvite(room.id, memberAddress(), member.publicKey, "neo", null)
        fx.sender.sent.clear()

        assertTrue(fx.host.renameMember(room.id, member.publicKey, "tank"))
        val stored = fx.store().loadAll().first()
        assertEquals("tank", stored.members.first { it.publicKey.contentEquals(member.publicKey) }.name)
        val rename = fx.sender.sent.first { RoomControls.decode(it.second).op == RoomControls.OP_RENAME }
        val (key, name) = RoomControls.decodeRename(RoomControls.decode(rename.second).payload)
        assertArrayEquals(member.publicKey, key)
        assertEquals("tank", name)
    }

    @Test
    fun `rename to a taken name fails`() {
        val fx = fixture()
        val room = createdRoom(fx)
        val member = memberPair()
        fx.host.createInvite(room.id, memberAddress(), member.publicKey, "neo", null)
        assertTrue(fx.host.handleJoin(room.id, member.publicKey, memberAddress(), "neo", null))
        // "Raven" is the founder's name, taken case-insensitively.
        assertFalse(fx.host.renameMember(room.id, member.publicKey, "RaVeN"))
    }

    @Test
    fun `leave removes the member and refreshes auth`() {
        val fx = fixture()
        val room = createdRoom(fx)
        val member = memberPair()
        fx.host.createInvite(room.id, memberAddress(), member.publicKey, "neo", null)
        assertTrue(fx.host.handleJoin(room.id, member.publicKey, memberAddress(), "neo", null))
        val deletedBefore = fx.tor.deletedCount

        assertTrue(fx.host.handleLeave(room.id, member.publicKey))
        val stored = fx.store().loadAll().first()
        assertTrue(stored.members.none { it.publicKey.contentEquals(member.publicKey) })
        assertTrue(fx.tor.deletedCount > deletedBefore)
    }

    @Test
    fun `delete room tears down the service and the record`() {
        val fx = fixture()
        val room = createdRoom(fx)
        val deletedBefore = fx.tor.deletedCount
        assertTrue(fx.host.deleteRoom(room.id))
        assertTrue(fx.store().loadAll().isEmpty())
        assertTrue(fx.tor.deletedCount > deletedBefore)
    }
}
