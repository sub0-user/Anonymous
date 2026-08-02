package org.server.anonymous.business

import org.server.anonymous.business.model.MemberStatus
import org.server.anonymous.business.model.RoomMember
import org.server.anonymous.business.model.RoomRecord
import org.server.anonymous.business.model.RoomType
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The founder side of rooms (Phase 4): creates and hosts room onion services, manages
 * client-auth entries (private) and entry keys (public), handles JOIN/LEAVE, and enforces
 * kicks by rotating the room key and re-wrapping it for the remaining members. One shared
 * listener socket receives every room service's connections; the room id in the first
 * control frame dispatches them. Control payloads ride the connection session keys, never
 * the room key.
 *
 * @Suppress TooManyFunctions, ReturnCount: one cohesive lifecycle; splitting it would scatter
 * the room state transitions, and the early returns are validation guards on every operation.
 */
@Suppress("TooManyFunctions", "ReturnCount")
class RoomHost(
    private val store: RoomStore,
    private val nodeStatus: () -> NodeStatus,
    private val torControl: () -> TorControl,
    private val identity: () -> Identity,
    private val sender: (String, ByteArray?, Byte, ByteArray) -> Boolean,
) {
    private val staticKeys: X25519KeyPair by lazy { IdentityKeys.x25519KeyPairFromSeed(identity().seed) }
    private var listener: ServerSocket? = null
    private var listening = false
    private val listenerExecutor =
        Executors.newSingleThreadExecutor { r -> Thread(r, "room-listen").apply { isDaemon = true } }

    /** The local port every room service maps to; created lazily so tests need no node. */
    fun listenerPort(): Int {
        if (listener == null || listener!!.isClosed) listener = ServerSocket(0)
        return listener!!.localPort
    }

    fun start() {
        listenerPort()
        for (record in store.loadAll().filter { it.isFounder }) {
            runCatching { ensureRoomService(record) }
        }
        startListening()
    }

    fun stop() {
        listening = false
        listenerExecutor.shutdownNow()
        runCatching { listener?.close() }
        for (record in store.loadAll().filter { it.isFounder }) {
            runCatching { torControl().deleteOnionService(record.serviceAddress) }
        }
    }

    @Suppress("ReturnCount") // validation early-returns; the success path is a single tail return
    fun createRoom(
        name: String,
        type: RoomType,
        myName: String,
    ): OpResult<RoomRecord> {
        val roomName = RoomNames.normalize(name)
        val display = RoomNames.normalize(myName)
        if (!RoomNames.isValid(roomName) || !RoomNames.isValid(display)) {
            return OpResult.Failure("Room name and your display name must not be empty")
        }
        val online = nodeStatus() as? NodeStatus.Online ?: return OpResult.Failure("Node is not online")
        val record =
            RoomRecord(
                id = RoomEnvelope.roomIdFromBytes(SessionCrypto.randomBytes(8)),
                name = roomName,
                type = type,
                isFounder = true,
                founderAddress = null,
                founderPublicKey = null,
                serviceSeed = SessionCrypto.randomBytes(32),
                serviceAddress = "",
                roomKey = RoomKeyWrap.newRoomKey(),
                keyVersion = 1,
                entryKey = if (type == RoomType.PUBLIC) EntryKey.generate() else null,
                myName = display,
                members = listOf(RoomMember(staticKeys.publicKey, display, address = online.address)),
            )
        val address =
            runCatching { ensureRoomService(record) }
                .getOrElse { return OpResult.Failure(it.message ?: "Failed to host the room") }
        store.save(record.copy(serviceAddress = address))
        return OpResult.Success(record.copy(serviceAddress = address))
    }

    /**
     * Creates an invite for an existing contact (private: per-member client-auth key + wrapped
     * room key; public: the shared entry key). Returns the opaque invite string.
     */
    fun createInvite(
        roomId: Long,
        memberAddress: String,
        memberPublicKey: ByteArray,
        name: String,
        expiryEpochSeconds: Long?,
    ): OpResult<String> {
        val record = store.loadAll().firstOrNull { it.id == roomId } ?: return OpResult.Failure("Room not found")
        if (!record.isFounder) return OpResult.Failure("Only the founder can invite")
        if (record.members.any { it.publicKey.contentEquals(memberPublicKey) }) {
            return OpResult.Failure("Already a member")
        }
        val display = RoomNames.normalize(name)
        if (!RoomNames.isValid(display) || !RoomNames.isUnique(record, display)) {
            return OpResult.Failure("That name is taken or empty")
        }
        return when (record.type) {
            RoomType.PRIVATE -> createPrivateInvite(record, memberAddress, memberPublicKey, display, expiryEpochSeconds)
            RoomType.PUBLIC -> {
                val invite =
                    PublicRoomInvite(
                        roomId = record.id,
                        serviceAddress = record.serviceAddress,
                        founderAddress = founderAddress(),
                        entryKey = record.entryKey ?: return OpResult.Failure("Room has no entry key"),
                        founderPublicKey = staticKeys.publicKey,
                    )
                OpResult.Success(InviteCodec.encode(invite))
            }
        }
    }

    /**
     * Handles a JOIN arriving on a room service. For private rooms the transport already
     * gated the caller; here the member's static key must match a member or pending invite
     * (expired invites are rejected and dropped). For public rooms the entry key must match
     * constant-time. On success the member is promoted/added, gets the current room key
     * wrapped for them, and the member list is re-broadcast.
     */
    fun handleJoin(
        roomId: Long,
        memberKey: ByteArray,
        memberAddress: String,
        requestedName: String,
        entryKey: String?,
    ): Boolean {
        val record = store.loadAll().firstOrNull { it.id == roomId } ?: return false
        if (!record.isFounder) return false
        val name = RoomNames.normalize(requestedName)
        if (!RoomNames.isValid(name)) return false
        if (record.type == RoomType.PUBLIC) {
            if (!entryKeyMatches(record, entryKey)) return false
            return joinPublic(record, memberKey, memberAddress, name)
        }
        return joinPrivate(record, memberKey, memberAddress, name)
    }

    /** Removes a member (LEAVE from the member, or the founder clearing an entry). */
    fun handleLeave(
        roomId: Long,
        memberKey: ByteArray,
    ): Boolean {
        val record = store.loadAll().firstOrNull { it.id == roomId } ?: return false
        if (!record.isFounder) return false
        val member = record.members.firstOrNull { it.publicKey.contentEquals(memberKey) } ?: return false
        val updated = record.copy(members = record.members - member)
        store.save(updated)
        val republished = runCatching { refreshClientAuth(updated) }.isSuccess
        if (!republished) return false
        broadcastMemberList(updated)
        return true
    }

    /**
     * Kicks a member: revokes their client-auth entry (private), rotates the room key so the
     * removed member can no longer decrypt, re-wraps for the remaining members, and notifies
     * the kicked member. Public rooms rotate the key too (re-join with the entry key remains
     * possible — that is the public-room tradeoff).
     */
    fun kickMember(
        roomId: Long,
        memberKey: ByteArray,
    ): Boolean {
        val record = store.loadAll().firstOrNull { it.id == roomId } ?: return false
        if (!record.isFounder) return false
        val member = record.members.firstOrNull { it.publicKey.contentEquals(memberKey) } ?: return false
        if (member.publicKey.contentEquals(staticKeys.publicKey)) return false
        val updated =
            record.copy(
                members =
                    record.members.map {
                        if (it.publicKey.contentEquals(memberKey)) it.copy(status = MemberStatus.KICKED) else it
                    },
            )
        store.save(updated)
        val revoked = runCatching { refreshClientAuth(updated) }.isSuccess
        if (!revoked) return false
        rotateRoomKey(updated, excludeKey = memberKey)
        sendControl(updated, member, RoomControls.OP_KICK, ByteArray(0))
        broadcastMemberList(updated)
        return true
    }

    /** Admin rename; the new name must be unique in the room. */
    fun renameMember(
        roomId: Long,
        memberKey: ByteArray,
        newName: String,
    ): Boolean {
        val record = store.loadAll().firstOrNull { it.id == roomId } ?: return false
        if (!record.isFounder) return false
        val display = RoomNames.normalize(newName)
        val member = record.members.firstOrNull { it.publicKey.contentEquals(memberKey) } ?: return false
        if (!RoomNames.isValid(display)) return false
        val taken = record.members.any { it != member && it.name.equals(display, ignoreCase = true) }
        if (taken) return false
        val updated =
            record.copy(
                members =
                    record.members.map {
                        if (it.publicKey.contentEquals(memberKey)) it.copy(name = display) else it
                    },
            )
        store.save(updated)
        broadcastControl(
            updated,
            RoomControls.OP_RENAME,
            RoomControls.encodeRename(memberKey, display),
        )
        return true
    }

    /** Removes the room entirely: tears down the service and deletes the local record. */
    fun deleteRoom(roomId: Long): Boolean {
        val record = store.loadAll().firstOrNull { it.id == roomId } ?: return false
        if (record.isFounder) runCatching { torControl().deleteOnionService(record.serviceAddress) }
        store.delete(roomId)
        return true
    }

    private fun createPrivateInvite(
        record: RoomRecord,
        memberAddress: String,
        memberPublicKey: ByteArray,
        display: String,
        expiryEpochSeconds: Long?,
    ): OpResult<String> {
        val pair = ClientAuthBlob.createKeyPair()
        val wrapKey = RoomKeyWrap.wrapKey(staticKeys.privateKey, memberPublicKey, record.id)
        val wrapped = RoomKeyWrap.wrap(record.roomKey, wrapKey, record.id)
        val member =
            RoomMember(
                publicKey = memberPublicKey,
                name = display,
                status = MemberStatus.INVITED,
                clientAuthPrivate = pair.privateScalar,
                wrappedRoomKey = wrapped,
                address = memberAddress,
                inviteExpiryEpochSeconds = expiryEpochSeconds,
            )
        val updated = record.copy(members = record.members + member)
        store.save(updated)
        val republished = runCatching { refreshClientAuth(updated) }.isSuccess
        if (!republished) {
            // Roll back the pending invite so a re-publish failure never leaves a half-open door.
            store.save(record)
            return OpResult.Failure("Could not publish the room — try the invite again")
        }
        val invite =
            PrivateRoomInvite(
                roomId = record.id,
                serviceAddress = record.serviceAddress,
                founderAddress = founderAddress(),
                clientAuthPrivate = pair.privateScalar,
                wrappedRoomKey = wrapped,
                founderPublicKey = staticKeys.publicKey,
                expiryEpochSeconds = expiryEpochSeconds,
            )
        return OpResult.Success(InviteCodec.encode(invite))
    }

    private fun joinPrivate(
        record: RoomRecord,
        memberKey: ByteArray,
        memberAddress: String,
        name: String,
    ): Boolean {
        val existing = record.members.firstOrNull { it.publicKey.contentEquals(memberKey) }
        if (existing == null) return false
        if (existing.status == MemberStatus.KICKED) return false
        if (existing.status == MemberStatus.INVITED) {
            val expiry = existing.inviteExpiryEpochSeconds
            if (expiry != null && expiry < System.currentTimeMillis() / 1000) {
                val dropped = record.copy(members = record.members - existing)
                store.save(dropped)
                refreshClientAuth(dropped)
                return false
            }
        }
        val nameOk = RoomNames.isUnique(record, name) || name.equals(existing.name, ignoreCase = true)
        if (!nameOk) return false
        val updated =
            record.copy(
                members =
                    record.members.map {
                        if (it.publicKey.contentEquals(memberKey)) {
                            it.copy(
                                name = if (name.equals(existing.name, ignoreCase = true)) it.name else name,
                                status = MemberStatus.MEMBER,
                                address = memberAddress,
                            )
                        } else {
                            it
                        }
                    },
            )
        store.save(updated)
        sendKeyUpdate(updated, updated.members.first { it.publicKey.contentEquals(memberKey) })
        broadcastMemberList(updated)
        return true
    }

    private fun joinPublic(
        record: RoomRecord,
        memberKey: ByteArray,
        memberAddress: String,
        name: String,
    ): Boolean {
        val existing = record.members.firstOrNull { it.publicKey.contentEquals(memberKey) }
        val nameOk =
            if (existing == null) {
                RoomNames.isUnique(record, name)
            } else {
                name.equals(existing.name, ignoreCase = true) || RoomNames.isUnique(record, name)
            }
        if (!nameOk) return false
        val wrapKey = RoomKeyWrap.wrapKey(staticKeys.privateKey, memberKey, record.id)
        val wrapped = RoomKeyWrap.wrap(record.roomKey, wrapKey, record.id)
        val member =
            existing?.copy(
                name = name,
                status = MemberStatus.MEMBER,
                address = memberAddress,
                wrappedRoomKey = wrapped,
            ) ?: RoomMember(memberKey, name, address = memberAddress, wrappedRoomKey = wrapped)
        val updated =
            record.copy(
                members =
                    if (existing == null) {
                        record.members + member
                    } else {
                        record.members.map { if (it.publicKey.contentEquals(memberKey)) member else it }
                    },
            )
        store.save(updated)
        sendKeyUpdate(updated, member)
        broadcastMemberList(updated)
        return true
    }

    /** Fresh room key, re-wrapped for every non-kicked member, delivered individually. */
    private fun rotateRoomKey(
        record: RoomRecord,
        excludeKey: ByteArray,
    ) {
        val newKey = RoomKeyWrap.newRoomKey()
        val nextVersion = record.keyVersion + 1
        val members =
            record.members.map { member ->
                if (member.status == MemberStatus.KICKED || member.publicKey.contentEquals(excludeKey)) {
                    member.copy(wrappedRoomKey = null)
                } else {
                    val wrapKey = RoomKeyWrap.wrapKey(staticKeys.privateKey, member.publicKey, record.id)
                    member.copy(wrappedRoomKey = RoomKeyWrap.wrap(newKey, wrapKey, record.id))
                }
            }
        val updated = record.copy(roomKey = newKey, keyVersion = nextVersion, members = members)
        store.save(updated)
        for (member in updated.members.filter { it.status == MemberStatus.MEMBER }) {
            sendKeyUpdate(updated, member)
        }
    }

    private fun sendKeyUpdate(
        record: RoomRecord,
        member: RoomMember,
    ) {
        val wrapped =
            member.wrappedRoomKey
                ?: run {
                    val wrapKey = RoomKeyWrap.wrapKey(staticKeys.privateKey, member.publicKey, record.id)
                    RoomKeyWrap.wrap(record.roomKey, wrapKey, record.id)
                }
        sendControl(
            record,
            member,
            RoomControls.OP_KEY_UPDATE,
            RoomControls.encodeKeyUpdate(record.keyVersion, wrapped),
        )
    }

    private fun broadcastMemberList(record: RoomRecord) {
        val entries =
            record.members
                .filter { it.status != MemberStatus.KICKED }
                .map { RoomControls.MemberEntry(it.publicKey, it.name, it.address) }
        broadcastControl(record, RoomControls.OP_MEMBER_LIST, RoomControls.encodeMemberList(record.name, entries))
    }

    private fun broadcastControl(
        record: RoomRecord,
        op: Int,
        payload: ByteArray,
    ) {
        for (member in record.members.filter { it.status == MemberStatus.MEMBER }) {
            sendControl(record, member, op, payload)
        }
    }

    private fun sendControl(
        record: RoomRecord,
        member: RoomMember,
        op: Int,
        payload: ByteArray,
    ) {
        val address = member.address ?: return
        sender(
            address,
            member.publicKey,
            WireProtocol.CONTENT_ROOM_CONTROL.toByte(),
            RoomControls.encode(op, record.id, payload),
        )
    }

    /**
     * Re-publishes a room service with the current client-auth list. Tor can stall on a
     * loaded network and the control socket times out (15s), so retry the delete+re-add
     * cycle with backoff: a transient stall must never turn an invite or kick into a
     * thrown exception. The delete is best-effort — re-adding a service that was not
     * actually deleted would collide, so a failed delete just retries the whole cycle.
     * Publishing is millisecond-fast on a healthy node; the 45s cap (≈3 stalled attempts)
     * bounds a bad moment so the dialog fails fast instead of hanging for minutes.
     */
    @Suppress("TooGenericExceptionCaught") // the retry swallows any control failure to keep publishing
    private fun refreshClientAuth(record: RoomRecord) {
        if (record.type != RoomType.PRIVATE || record.serviceAddress.isEmpty()) return
        var lastError: Throwable? = null
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
        while (System.nanoTime() < deadline) {
            try {
                runCatching { torControl().deleteOnionService(record.serviceAddress) }
                ensureRoomService(record)
                return
            } catch (t: Throwable) {
                lastError = t
                Thread.sleep(1000)
            }
        }
        error("room service re-publish never succeeded: ${lastError?.message}")
    }

    private fun ensureRoomService(record: RoomRecord): String {
        val blobs =
            if (record.type == RoomType.PRIVATE) {
                record.members
                    .filter { it.status != MemberStatus.KICKED }
                    .mapNotNull { member ->
                        member.clientAuthPrivate?.let { private ->
                            ClientAuthBlob.torAddOnionBlob(
                                ClientAuthKeyPair(private, IdentityKeys.x25519PublicKeyFromScalar(private)),
                            )
                        }
                    }
            } else {
                emptyList()
            }
        val address =
            if (blobs.isEmpty()) {
                torControl().addOnionService(record.serviceSeed, 80, "127.0.0.1", listenerPort())
            } else {
                torControl().addOnionServiceWithClientAuth(
                    record.serviceSeed,
                    80,
                    "127.0.0.1",
                    listenerPort(),
                    blobs,
                )
            }
        if (record.serviceAddress.isNotEmpty() && address != record.serviceAddress) {
            error("room service address changed unexpectedly")
        }
        return address
    }

    private fun entryKeyMatches(
        record: RoomRecord,
        presented: String?,
    ): Boolean {
        val expected = record.entryKey ?: return false
        if (presented == null) return false
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            presented.toByteArray(Charsets.UTF_8),
        )
    }

    private fun founderAddress(): String {
        val online = nodeStatus() as? NodeStatus.Online ?: error("node offline")
        return online.address
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // accept fails during shutdown — retry
    private fun startListening() {
        if (listening) return // the node watchdog may re-trigger start() after a tor restart
        listening = true
        listenerExecutor.execute {
            while (!Thread.currentThread().isInterrupted) {
                val socket =
                    try {
                        listener?.accept()
                    } catch (t: Throwable) {
                        null
                    }
                if (socket != null) handleRoomConnection(socket)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // malformed input is dropped
    private fun handleRoomConnection(socket: Socket) {
        try {
            val online = nodeStatus() as? NodeStatus.Online ?: return
            val session = MessageSession.respond(socket, staticKeys, online.address)
            try {
                val received = session.receiveMessage()
                if (received.contentType.toInt() != WireProtocol.CONTENT_ROOM_CONTROL) return
                val frame = RoomControls.decode(received.body)
                when (frame.op) {
                    RoomControls.OP_JOIN -> {
                        val join = RoomControls.decodeJoin(frame.payload)
                        handleJoin(frame.roomId, session.peerPublicKey, session.peerAddress, join.name, join.entryKey)
                    }
                    RoomControls.OP_LEAVE -> handleLeave(frame.roomId, session.peerPublicKey)
                    else -> Unit
                }
            } finally {
                session.close()
            }
        } catch (t: Throwable) {
            // Malformed or unauthorized connection — drop silently and keep listening.
        } finally {
            runCatching { socket.close() }
        }
    }
}
