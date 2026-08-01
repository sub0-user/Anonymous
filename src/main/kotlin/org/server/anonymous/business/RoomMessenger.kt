package org.server.anonymous.business

import org.server.anonymous.business.model.RoomMember
import org.server.anonymous.business.model.RoomMessageItem
import org.server.anonymous.business.model.RoomRecord
import org.server.anonymous.business.model.RoomType
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The member side of rooms (Phase 4): accepts invites, joins room services, fans out room
 * messages to the other members directly, and handles the room controls that arrive on the
 * identity service (member list, key updates, renames, kicks). Messages are held in memory
 * like 1:1 messages; room-message history persistence is a later phase.
 *
 * @Suppress TooManyFunctions, ReturnCount: one cohesive member surface over a small fixed op
 * set; splitting it would scatter the room state transitions, and the early returns are
 * validation guards on every operation.
 */
@Suppress("TooManyFunctions", "ReturnCount")
class RoomMessenger(
    private val store: RoomStore,
    private val identity: () -> Identity,
    private val sender: (String, ByteArray?, Byte, ByteArray) -> Boolean,
    private val clientAuthInstaller: OnionClientAuth? = null,
    /** Encrypted at-rest history (Phase A1); null keeps the in-memory-only behavior for tests. */
    private val roomHistory: MessageJournal<RoomMessageItem>? = null,
) {
    private val keys: X25519KeyPair by lazy { IdentityKeys.x25519KeyPairFromSeed(identity().seed) }
    private val messages = mutableMapOf<Long, MutableList<RoomMessageItem>>()
    private val listeners = CopyOnWriteArrayList<(RoomMessageItem) -> Unit>()
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
    private var nextId = 1L

    init {
        // Restore persisted history; a later record for the same id (a status change) wins.
        roomHistory?.load()?.forEach { (roomId, item) ->
            val list = messages.getOrPut(roomId) { mutableListOf() }
            val index = list.indexOfLast { it.id == item.id }
            if (index >= 0) list[index] = item else list += item
            if (item.id >= nextId) nextId = item.id + 1
        }
    }

    fun rooms(): List<RoomRecord> = store.loadAll()

    fun messagesFor(roomId: Long): List<RoomMessageItem> =
        synchronized(messages) {
            messages[roomId]?.toList() ?: emptyList()
        }

    fun addMessageListener(listener: (RoomMessageItem) -> Unit) {
        listeners += listener
    }

    /** Deletes one room's history from disk and memory. */
    fun clearHistory(roomId: Long) {
        synchronized(messages) { messages.remove(roomId) }
        roomHistory?.clear(roomId)
    }

    /**
     * Accepts an invite: unwraps the room key (private) or records the door key (public),
     * installs the client-auth key for private rooms, and persists the member's view.
     * The room still needs [join] before messages flow.
     */
    fun acceptInvite(
        inviteText: String,
        myName: String,
    ): OpResult<RoomRecord> {
        val invite =
            runCatching { InviteCodec.decode(inviteText) }.getOrElse {
                return OpResult.Failure("Invalid invite")
            }
        val display = RoomNames.normalize(myName)
        if (!RoomNames.isValid(display)) return OpResult.Failure("Your display name must not be empty")
        return when (invite) {
            is PrivateRoomInvite -> acceptPrivate(invite, display)
            is PublicRoomInvite -> acceptPublic(invite, display)
        }
    }

    /** Sends JOIN to the room service so the founder admits us and syncs the member list. */
    fun join(roomId: Long): Boolean {
        val record = store.loadAll().firstOrNull { it.id == roomId } ?: return false
        val body =
            RoomControls.encode(
                RoomControls.OP_JOIN,
                roomId,
                RoomControls.encodeJoin(record.myName, record.entryKey),
            )
        return sender(record.serviceAddress, null, WireProtocol.CONTENT_ROOM_CONTROL.toByte(), body)
    }

    /** Sends LEAVE and drops the local record. */
    fun leaveRoom(roomId: Long): Boolean {
        val record = store.loadAll().firstOrNull { it.id == roomId } ?: return false
        val body = RoomControls.encode(RoomControls.OP_LEAVE, roomId)
        sender(record.serviceAddress, null, WireProtocol.CONTENT_ROOM_CONTROL.toByte(), body)
        store.delete(roomId)
        return true
    }

    /** Deletes the local record (the founder removed us — KICK already delivered). */
    fun dropRoom(roomId: Long) {
        store.delete(roomId)
    }

    /** Fans out one text message to every other member; returns how many accepted it. */
    fun sendMessage(
        roomId: Long,
        body: String,
    ): OpResult<Int> {
        val text = body.trim()
        val record = store.loadAll().firstOrNull { it.id == roomId }
        if (text.isEmpty()) return OpResult.Failure("Message is empty")
        if (record == null) return OpResult.Failure("Room not found")
        val message = RoomMessageItem(nextId(), roomId, keys.publicKey, text, nowLabel(), isOutgoing = true)
        synchronized(messages) { messages.getOrPut(roomId) { mutableListOf() } += message }
        roomHistory?.append(roomId, message)
        notify(message)
        val envelope =
            RoomEnvelope.encodeRoomMessage(
                RoomEnvelope.RoomMessage(
                    roomId = roomId,
                    keyVersion = record.keyVersion,
                    nonce = SessionCrypto.randomNonce(),
                    ciphertext =
                        SessionCrypto.encrypt(
                            record.roomKey,
                            SessionCrypto.randomNonce(),
                            text.toByteArray(Charsets.UTF_8),
                            RoomEnvelope.roomAad(roomId, record.keyVersion),
                        ),
                ),
            )
        val delivered =
            record.members.count { member ->
                member.status == org.server.anonymous.business.model.MemberStatus.MEMBER &&
                    !member.publicKey.contentEquals(keys.publicKey) &&
                    member.address != null &&
                    sender(member.address!!, member.publicKey, WireProtocol.CONTENT_ROOM_MSG.toByte(), envelope)
            }
        return OpResult.Success(delivered)
    }

    /** Inbound router for room frames arriving on the identity service. */
    fun handleInbound(
        peerKey: ByteArray,
        peerAddress: String,
        contentType: Byte,
        body: ByteArray,
    ) {
        when (contentType.toInt()) {
            WireProtocol.CONTENT_ROOM_MSG -> handleRoomMessage(peerKey, peerAddress, body)
            WireProtocol.CONTENT_ROOM_CONTROL -> handleControl(peerKey, peerAddress, body)
            else -> Unit
        }
    }

    private fun acceptPrivate(
        invite: PrivateRoomInvite,
        display: String,
    ): OpResult<RoomRecord> {
        val wrapKey = RoomKeyWrap.wrapKey(keys.privateKey, invite.founderPublicKey, invite.roomId)
        val roomKey =
            runCatching { RoomKeyWrap.unwrap(invite.wrappedRoomKey, wrapKey, invite.roomId) }.getOrElse {
                return OpResult.Failure("Could not open the room key — is this invite for you?")
            }
        clientAuthInstaller?.install(invite.serviceAddress, invite.clientAuthPrivate)
        val record =
            RoomRecord(
                id = invite.roomId,
                name = invite.serviceAddress, // placeholder until MEMBER_LIST carries the room name
                type = RoomType.PRIVATE,
                isFounder = false,
                founderAddress = invite.founderAddress,
                founderPublicKey = invite.founderPublicKey,
                serviceSeed = ByteArray(0),
                serviceAddress = invite.serviceAddress,
                roomKey = roomKey,
                keyVersion = 1,
                entryKey = null,
                myName = display,
                members = listOf(RoomMember(keys.publicKey, display)),
            )
        store.save(record)
        return OpResult.Success(record)
    }

    private fun acceptPublic(
        invite: PublicRoomInvite,
        display: String,
    ): OpResult<RoomRecord> {
        val record =
            RoomRecord(
                id = invite.roomId,
                name = invite.serviceAddress,
                type = RoomType.PUBLIC,
                isFounder = false,
                founderAddress = invite.founderAddress,
                founderPublicKey = invite.founderPublicKey,
                serviceSeed = ByteArray(0),
                serviceAddress = invite.serviceAddress,
                roomKey = ByteArray(0),
                keyVersion = 0,
                entryKey = invite.entryKey,
                myName = display,
                members = listOf(RoomMember(keys.publicKey, display)),
            )
        store.save(record)
        return OpResult.Success(record)
    }

    @Suppress("UnusedParameter") // part of the uniform inbound router contract (see handleInbound)
    private fun handleRoomMessage(
        peerKey: ByteArray,
        peerAddress: String,
        body: ByteArray,
    ) {
        val envelope =
            runCatching { RoomEnvelope.decodeRoomMessage(body) }.getOrNull() ?: return
        val record = store.loadAll().firstOrNull { it.id == envelope.roomId } ?: return
        if (envelope.keyVersion != record.keyVersion) return // stale key — a rotation is on its way
        val plaintext =
            runCatching {
                SessionCrypto.decrypt(
                    record.roomKey,
                    envelope.nonce,
                    envelope.ciphertext,
                    RoomEnvelope.roomAad(envelope.roomId, envelope.keyVersion),
                )
            }.getOrNull() ?: return
        val message =
            RoomMessageItem(
                id = nextId(),
                roomId = record.id,
                senderPublicKey = peerKey,
                body = plaintext.toString(Charsets.UTF_8),
                timeLabel = nowLabel(),
                isOutgoing = false,
            )
        synchronized(messages) { messages.getOrPut(record.id) { mutableListOf() } += message }
        roomHistory?.append(record.id, message)
        notify(message)
    }

    @Suppress("UnusedParameter") // part of the uniform inbound router contract (see handleInbound)
    private fun handleControl(
        peerKey: ByteArray,
        peerAddress: String,
        body: ByteArray,
    ) {
        val frame = runCatching { RoomControls.decode(body) }.getOrNull() ?: return
        val record = store.loadAll().firstOrNull { it.id == frame.roomId } ?: return
        when (frame.op) {
            RoomControls.OP_MEMBER_LIST -> applyMemberList(record, frame.payload)
            RoomControls.OP_KEY_UPDATE -> applyKeyUpdate(record, frame.payload)
            RoomControls.OP_RENAME -> applyRename(record, frame.payload)
            RoomControls.OP_KICK -> dropRoom(record.id)
            else -> Unit
        }
    }

    private fun applyMemberList(
        record: RoomRecord,
        payload: ByteArray,
    ) {
        val list = runCatching { RoomControls.decodeMemberList(payload) }.getOrNull() ?: return
        val updated =
            record.copy(
                name = list.roomName,
                myName = list.members.firstOrNull { it.publicKey.contentEquals(keys.publicKey) }?.name ?: record.myName,
                members =
                    list.members.map { entry ->
                        val existing = record.members.firstOrNull { it.publicKey.contentEquals(entry.publicKey) }
                        existing?.copy(
                            name = entry.name,
                            status = org.server.anonymous.business.model.MemberStatus.MEMBER,
                            address = entry.address ?: existing.address,
                        )
                            ?: RoomMember(entry.publicKey, entry.name, address = entry.address)
                    },
            )
        store.save(updated)
    }

    private fun applyKeyUpdate(
        record: RoomRecord,
        payload: ByteArray,
    ) {
        val (version, wrapped) = runCatching { RoomControls.decodeKeyUpdate(payload) }.getOrNull() ?: return
        val founderKey = record.founderPublicKey ?: return
        val wrapKey = RoomKeyWrap.wrapKey(keys.privateKey, founderKey, record.id)
        val roomKey = runCatching { RoomKeyWrap.unwrap(wrapped, wrapKey, record.id) }.getOrNull() ?: return
        store.save(record.copy(roomKey = roomKey, keyVersion = version))
    }

    private fun applyRename(
        record: RoomRecord,
        payload: ByteArray,
    ) {
        val (key, name) = runCatching { RoomControls.decodeRename(payload) }.getOrNull() ?: return
        store.save(
            record.copy(
                members =
                    record.members.map {
                        if (it.publicKey.contentEquals(key)) it.copy(name = name) else it
                    },
            ),
        )
    }

    private fun notify(message: RoomMessageItem) {
        listeners.forEach { it(message) }
    }

    private fun nextId(): Long = synchronized(messages) { nextId++ }

    private fun nowLabel(): String = LocalTime.now().format(timeFormat)
}
