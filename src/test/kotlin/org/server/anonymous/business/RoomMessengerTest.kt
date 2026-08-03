package org.server.anonymous.business

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.server.anonymous.business.model.MemberStatus
import org.server.anonymous.business.model.ReplyRef
import org.server.anonymous.business.model.RoomMember
import org.server.anonymous.business.model.RoomRecord
import org.server.anonymous.business.model.RoomType
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class RoomMessengerTest {
    private val identity = Identity(ByteArray(32) { 5 }, Instant.now())
    private val myKeys = IdentityKeys.x25519KeyPairFromSeed(identity.seed)
    private val founderKeys = IdentityKeys.x25519KeyPairFromSeed(ByteArray(32) { 6 })
    private val founderAddress = "a".repeat(56) + ".onion"
    private val serviceAddress = "c".repeat(56) + ".onion"
    private val roomId = 0x1122334455667788L
    private val roomKey = RoomKeyWrap.newRoomKey()

    private class FakeTorControl : TorControl {
        var hupCount = 0

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
        ): String = "a".repeat(56) + ".onion"

        override fun deleteOnionService(address: String) = Unit

        override fun signalHup() {
            hupCount++
        }

        override fun close() = Unit
    }

    private class Fixture(
        val messenger: RoomMessenger,
        val store: RoomStore,
        val sender: MutableList<Triple<String, Byte, ByteArray>>,
        val tor: FakeTorControl,
    )

    private fun newDir(): Path = Files.createTempDirectory("anonymous-room-member").also { it.toFile().deleteOnExit() }

    private fun fixture(): Fixture {
        val storeDir = newDir()
        val tor = FakeTorControl()
        val sent = mutableListOf<Triple<String, Byte, ByteArray>>()
        val messenger =
            RoomMessenger(RoomStore(storeDir), { identity }, { address, _, type, body ->
                sent += Triple(address, type, body)
                true
            })
        return Fixture(messenger, RoomStore(storeDir), sent, tor)
    }

    private fun memberRecord(
        members: List<RoomMember> = listOf(RoomMember(myKeys.publicKey, "me")),
        key: ByteArray = roomKey,
        version: Int = 1,
    ): RoomRecord =
        RoomRecord(
            id = roomId,
            name = "dev den",
            type = RoomType.PRIVATE,
            isFounder = false,
            founderAddress = founderAddress,
            founderPublicKey = founderKeys.publicKey,
            serviceSeed = ByteArray(0),
            serviceAddress = serviceAddress,
            roomKey = key,
            keyVersion = version,
            entryKey = null,
            myName = "me",
            members = members,
        )

    private fun privateInviteText(): String {
        val wrapKey = RoomKeyWrap.wrapKey(founderKeys.privateKey, myKeys.publicKey, roomId)
        val wrapped = RoomKeyWrap.wrap(roomKey, wrapKey, roomId)
        return InviteCodec.encode(
            PrivateRoomInvite(
                roomId = roomId,
                serviceAddress = serviceAddress,
                founderAddress = founderAddress,
                wrappedRoomKey = wrapped,
                founderPublicKey = founderKeys.publicKey,
            ),
        )
    }

    @Test
    fun `accepting a private invite unwraps the room key without client auth`() {
        val fx = fixture()
        val result = fx.messenger.acceptInvite(privateInviteText(), "me")
        assertTrue(result is OpResult.Success)
        val record = fx.store.loadAll().single()
        assertEquals(RoomType.PRIVATE, record.type)
        assertFalse(record.isFounder)
        assertArrayEquals(roomKey, record.roomKey)
        assertArrayEquals(founderKeys.publicKey, record.founderPublicKey)
        assertEquals(1, record.members.size)
        // Membership is app-layer — no client-auth file is installed and Tor is not reloaded.
        assertEquals(0, fx.tor.hupCount)
    }

    @Test
    fun `accepting a public invite stores the door key without client auth`() {
        val fx = fixture()
        val entryKey = EntryKey.generate()
        val invite =
            InviteCodec.encode(
                PublicRoomInvite(roomId, serviceAddress, founderAddress, entryKey, founderKeys.publicKey),
            )
        val result = fx.messenger.acceptInvite(invite, "me")
        assertTrue(result is OpResult.Success)
        val record = fx.store.loadAll().single()
        assertEquals(RoomType.PUBLIC, record.type)
        assertEquals(entryKey, record.entryKey)
        assertEquals(0, fx.tor.hupCount)
    }

    @Test
    fun `accepting a garbage invite fails`() {
        val fx = fixture()
        assertTrue(fx.messenger.acceptInvite("not-an-invite", "me") is OpResult.Failure)
        assertTrue(fx.store.loadAll().isEmpty())
    }

    @Test
    fun `join sends a JOIN control with name and entry key`() {
        val fx = fixture()
        val entryKey = EntryKey.generate()
        fx.store.save(
            memberRecord().copy(
                type = RoomType.PUBLIC,
                entryKey = entryKey,
                roomKey = ByteArray(0),
                keyVersion = 0,
            ),
        )
        assertTrue(fx.messenger.join(roomId))
        val (to, type, body) = fx.sender.single()
        assertEquals(serviceAddress, to)
        assertEquals(WireProtocol.CONTENT_ROOM_CONTROL.toInt(), type.toInt())
        val frame = RoomControls.decode(body)
        assertEquals(RoomControls.OP_JOIN, frame.op)
        val join = RoomControls.decodeJoin(frame.payload)
        assertEquals("me", join.name)
        assertEquals(entryKey, join.entryKey)
    }

    @Test
    fun `sendMessage fans out to every other member and stores the message`() {
        val fx = fixture()
        val alice = IdentityKeys.x25519KeyPairFromSeed(ByteArray(32) { 21 })
        val bob = IdentityKeys.x25519KeyPairFromSeed(ByteArray(32) { 22 })
        fx.store.save(
            memberRecord(
                members =
                    listOf(
                        RoomMember(myKeys.publicKey, "me"),
                        RoomMember(alice.publicKey, "alice", address = "b".repeat(56) + ".onion"),
                        RoomMember(bob.publicKey, "bob", address = "d".repeat(56) + ".onion"),
                        RoomMember(ByteArray(32) { 99 }, "ghost", status = MemberStatus.KICKED),
                    ),
            ),
        )
        val result = fx.messenger.sendMessage(roomId, "hello room")
        assertTrue(result is OpResult.Success)
        assertEquals(2, (result as OpResult.Success).value)
        assertEquals(2, fx.sender.size)
        assertTrue(fx.sender.all { it.second.toInt() == WireProtocol.CONTENT_ROOM_MSG })
        val stored = fx.messenger.messagesFor(roomId).single()
        assertEquals("hello room", stored.body)
        assertTrue(stored.isOutgoing)
    }

    @Test
    fun `undelivered fan-out is retried until the member accepts`() {
        val store = RoomStore(newDir())
        val alice = IdentityKeys.x25519KeyPairFromSeed(ByteArray(32) { 21 })
        store.save(
            memberRecord(
                members =
                    listOf(
                        RoomMember(myKeys.publicKey, "me"),
                        RoomMember(alice.publicKey, "alice", address = "b".repeat(56) + ".onion"),
                    ),
            ),
        )
        var accept = false
        var attempts = 0
        var delivered = false
        val messenger =
            RoomMessenger(
                store,
                { identity },
                { _, _, _, _ ->
                    attempts++
                    if (accept) {
                        delivered = true
                        true
                    } else {
                        false
                    }
                },
                retryScanMillis = 50,
                retryBackoffBaseMillis = 50,
            )
        try {
            val first = messenger.sendMessage(roomId, "to an offline member")
            assertTrue(first is OpResult.Success)
            assertEquals(0, (first as OpResult.Success).value) // nobody accepted on the first pass
            accept = true
            // The outbox scan keeps retrying until the member accepts.
            val deadline = System.currentTimeMillis() + 5_000
            while (!delivered && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
            assertTrue(delivered, "fan-out retry never delivered")
            assertTrue(attempts >= 2)
        } finally {
            messenger.stop()
        }
    }

    @Test
    fun `inbound room message is decrypted and stored`() {
        val fx = fixture()
        val alice = IdentityKeys.x25519KeyPairFromSeed(ByteArray(32) { 21 })
        fx.store.save(memberRecord())
        val nonce = SessionCrypto.randomNonce()
        val body =
            RoomEnvelope.encodeRoomMessage(
                RoomEnvelope.RoomMessage(
                    roomId = roomId,
                    keyVersion = 1,
                    nonce = nonce,
                    ciphertext =
                        SessionCrypto.encrypt(
                            roomKey,
                            nonce,
                            "hey from alice".toByteArray(Charsets.UTF_8),
                            RoomEnvelope.roomAad(roomId, 1),
                        ),
                ),
            )
        val notified = mutableListOf<org.server.anonymous.business.model.RoomMessageItem>()
        fx.messenger.addMessageListener { notified += it }

        fx.messenger.handleInbound(
            alice.publicKey,
            "b".repeat(56) + ".onion",
            WireProtocol.CONTENT_ROOM_MSG.toByte(),
            body,
        )
        val stored = fx.messenger.messagesFor(roomId).single()
        assertEquals("hey from alice", stored.body)
        assertFalse(stored.isOutgoing)
        assertEquals(1, notified.size)
    }

    @Test
    fun `inbound room reply is decrypted with its reference`() {
        val fx = fixture()
        val alice = IdentityKeys.x25519KeyPairFromSeed(ByteArray(32) { 21 })
        fx.store.save(memberRecord())
        val nonce = SessionCrypto.randomNonce()
        val ref = ReplyRef(ByteArray(32) { 21 }, "alice", "the question")
        val body =
            RoomEnvelope.encodeRoomMessage(
                RoomEnvelope.RoomMessage(
                    roomId = roomId,
                    keyVersion = 1,
                    nonce = nonce,
                    ciphertext =
                        SessionCrypto.encrypt(
                            roomKey,
                            nonce,
                            ReplyCodec.encode("the answer", ref),
                            RoomEnvelope.roomAad(roomId, 1),
                        ),
                ),
            )

        fx.messenger.handleInbound(
            alice.publicKey,
            "b".repeat(56) + ".onion",
            WireProtocol.CONTENT_ROOM_MSG.toByte(),
            body,
        )
        val stored = fx.messenger.messagesFor(roomId).single()
        assertEquals("the answer", stored.body)
        assertArrayEquals(ByteArray(32) { 21 }, stored.replyTo!!.senderKey)
        assertEquals("the question", stored.replyTo!!.text)
    }

    @Test
    fun `inbound message with a stale key version is dropped`() {
        val fx = fixture()
        fx.store.save(memberRecord(version = 2))
        val nonce = SessionCrypto.randomNonce()
        val body =
            RoomEnvelope.encodeRoomMessage(
                RoomEnvelope.RoomMessage(
                    roomId,
                    1,
                    nonce,
                    SessionCrypto.encrypt(roomKey, nonce, "stale".toByteArray(), RoomEnvelope.roomAad(roomId, 1)),
                ),
            )
        fx.messenger.handleInbound(
            ByteArray(32) { 1 },
            "b".repeat(56) + ".onion",
            WireProtocol.CONTENT_ROOM_MSG.toByte(),
            body,
        )
        assertTrue(fx.messenger.messagesFor(roomId).isEmpty())
    }

    @Test
    fun `key update rotates the room key`() {
        val fx = fixture()
        fx.store.save(memberRecord())
        val newKey = RoomKeyWrap.newRoomKey()
        val wrapKey = RoomKeyWrap.wrapKey(founderKeys.privateKey, myKeys.publicKey, roomId)
        val wrapped = RoomKeyWrap.wrap(newKey, wrapKey, roomId)
        val body = RoomControls.encode(RoomControls.OP_KEY_UPDATE, roomId, RoomControls.encodeKeyUpdate(2, wrapped))

        fx.messenger.handleInbound(
            founderKeys.publicKey,
            founderAddress,
            WireProtocol.CONTENT_ROOM_CONTROL.toByte(),
            body,
        )
        val record = fx.store.loadAll().single()
        assertArrayEquals(newKey, record.roomKey)
        assertEquals(2, record.keyVersion)
    }

    @Test
    fun `member list syncs names addresses and the room name`() {
        val fx = fixture()
        fx.store.save(memberRecord())
        val alice = IdentityKeys.x25519KeyPairFromSeed(ByteArray(32) { 21 })
        val payload =
            RoomControls.encodeMemberList(
                "renamed room",
                listOf(
                    RoomControls.MemberEntry(myKeys.publicKey, "me-renamed", "b".repeat(56) + ".onion"),
                    RoomControls.MemberEntry(alice.publicKey, "alice", "d".repeat(56) + ".onion"),
                ),
            )
        val body = RoomControls.encode(RoomControls.OP_MEMBER_LIST, roomId, payload)

        fx.messenger.handleInbound(
            founderKeys.publicKey,
            founderAddress,
            WireProtocol.CONTENT_ROOM_CONTROL.toByte(),
            body,
        )
        val record = fx.store.loadAll().single()
        assertEquals("renamed room", record.name)
        assertEquals("me-renamed", record.myName)
        assertEquals(2, record.members.size)
        val aliceEntry = record.members.first { it.publicKey.contentEquals(alice.publicKey) }
        assertEquals("d".repeat(56) + ".onion", aliceEntry.address)
    }

    @Test
    fun `rename updates the member name`() {
        val fx = fixture()
        val alice = IdentityKeys.x25519KeyPairFromSeed(ByteArray(32) { 21 })
        fx.store.save(
            memberRecord(
                members =
                    listOf(
                        RoomMember(myKeys.publicKey, "me"),
                        RoomMember(alice.publicKey, "alice", address = "b".repeat(56) + ".onion"),
                    ),
            ),
        )
        val body =
            RoomControls.encode(
                RoomControls.OP_RENAME,
                roomId,
                RoomControls.encodeRename(alice.publicKey, "tank"),
            )

        fx.messenger.handleInbound(
            founderKeys.publicKey,
            founderAddress,
            WireProtocol.CONTENT_ROOM_CONTROL.toByte(),
            body,
        )
        val record = fx.store.loadAll().single()
        assertEquals("tank", record.members.first { it.publicKey.contentEquals(alice.publicKey) }.name)
    }

    @Test
    fun `kick removes the local room`() {
        val fx = fixture()
        fx.store.save(memberRecord())
        val body = RoomControls.encode(RoomControls.OP_KICK, roomId)

        fx.messenger.handleInbound(
            founderKeys.publicKey,
            founderAddress,
            WireProtocol.CONTENT_ROOM_CONTROL.toByte(),
            body,
        )
        assertTrue(fx.store.loadAll().isEmpty())
    }

    @Test
    fun `leave sends LEAVE and drops the record`() {
        val fx = fixture()
        fx.store.save(memberRecord())
        assertTrue(fx.messenger.leaveRoom(roomId))
        val (to, type, body) = fx.sender.single()
        assertEquals(serviceAddress, to)
        val frame = RoomControls.decode(body)
        assertEquals(RoomControls.OP_LEAVE, frame.op)
        assertTrue(fx.store.loadAll().isEmpty())
    }

    @Test
    fun `join returns false when the transport refuses`() {
        val storeDir = newDir()
        val store = RoomStore(storeDir)
        val refused =
            RoomMessenger(store, { identity }, { _, _, _, _ -> false })
        store.save(
            memberRecord().copy(
                type = RoomType.PUBLIC,
                entryKey = EntryKey.generate(),
                roomKey = ByteArray(0),
                keyVersion = 0,
            ),
        )
        assertFalse(refused.join(roomId))
        refused.stop()
    }
}
