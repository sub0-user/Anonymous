package org.server.anonymous.business

/**
 * Room control operations (Phase 4), carried as `CONTENT_ROOM_CONTROL` frames. Control
 * payloads are always encrypted with the connection's session keys (never the room key),
 * because they carry key material and membership truth.
 *
 * @Suppress TooManyFunctions: one cohesive wire codec for a small fixed op set; splitting
 * it would scatter the format definition.
 */
@Suppress("TooManyFunctions")
object RoomControls {
    const val OP_JOIN = 1
    const val OP_LEAVE = 2
    const val OP_MEMBER_LIST = 3
    const val OP_KEY_UPDATE = 4
    const val OP_RENAME = 5
    const val OP_KICK = 6

    data class ControlFrame(
        val op: Int,
        val roomId: Long,
        val payload: ByteArray,
    )

    data class MemberEntry(
        val publicKey: ByteArray,
        val name: String,
    )

    fun encode(
        op: Int,
        roomId: Long,
        payload: ByteArray = ByteArray(0),
    ): ByteArray = byteArrayOf(op.toByte()) + RoomEnvelope.roomIdToBytes(roomId) + payload

    fun decode(body: ByteArray): ControlFrame {
        check(body.size >= 1 + RoomEnvelope.ROOM_ID_LENGTH) { "control frame too short" }
        val op = body[0].toInt() and 0xFF
        check(op in OP_JOIN..OP_KICK) { "unknown control op: $op" }
        val roomId = RoomEnvelope.roomIdFromBytes(body.copyOfRange(1, 1 + RoomEnvelope.ROOM_ID_LENGTH))
        return ControlFrame(op, roomId, body.copyOfRange(1 + RoomEnvelope.ROOM_ID_LENGTH, body.size))
    }

    // JOIN — private rooms carry an empty payload (the Tor client-auth layer already gated
    // the connection); public rooms carry the entry key.

    fun encodeJoin(entryKey: String): ByteArray {
        val key = entryKey.toByteArray(Charsets.UTF_8)
        check(key.size <= 255) { "entry key too long" }
        return byteArrayOf(key.size.toByte()) + key
    }

    /** The presented entry key, or null for a private-room JOIN (empty payload). */
    fun decodeJoin(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        check(payload.size >= 1) { "join payload too short" }
        val length = payload[0].toInt() and 0xFF
        check(payload.size == 1 + length) { "malformed join payload" }
        return payload.copyOfRange(1, payload.size).toString(Charsets.UTF_8)
    }

    // MEMBER_LIST — the authoritative name map: [count:1] then [pub:32][nameLen:1][name].

    fun encodeMemberList(members: List<MemberEntry>): ByteArray {
        check(members.size <= 255) { "too many members" }
        val body = java.io.ByteArrayOutputStream()
        body.write(members.size)
        for (member in members) {
            check(member.publicKey.size == 32) { "member key must be 32 bytes" }
            val name = member.name.toByteArray(Charsets.UTF_8)
            check(name.size in 1..255) { "member name length out of range" }
            body.write(member.publicKey)
            body.write(name.size)
            body.write(name)
        }
        return body.toByteArray()
    }

    fun decodeMemberList(payload: ByteArray): List<MemberEntry> {
        check(payload.isNotEmpty()) { "member list too short" }
        val count = payload[0].toInt() and 0xFF
        var offset = 1
        val members = mutableListOf<MemberEntry>()
        repeat(count) {
            check(offset + 32 + 1 <= payload.size) { "malformed member list" }
            val key = payload.copyOfRange(offset, offset + 32)
            offset += 32
            val nameLen = payload[offset].toInt() and 0xFF
            offset += 1
            check(nameLen in 1..255 && offset + nameLen <= payload.size) { "malformed member name" }
            members += MemberEntry(key, payload.copyOfRange(offset, offset + nameLen).toString(Charsets.UTF_8))
            offset += nameLen
        }
        check(offset == payload.size) { "trailing bytes in member list" }
        return members
    }

    // KEY_UPDATE — [version:1][wrappedLen:2][wrapped room key for this member].

    fun encodeKeyUpdate(
        version: Int,
        wrappedKey: ByteArray,
    ): ByteArray {
        check(wrappedKey.size <= 0xFFFF) { "wrapped key too large" }
        return byteArrayOf(
            version.toByte(),
            (wrappedKey.size ushr 8).toByte(),
            wrappedKey.size.toByte(),
        ) + wrappedKey
    }

    fun decodeKeyUpdate(payload: ByteArray): Pair<Int, ByteArray> {
        check(payload.size >= 3) { "key update too short" }
        val version = payload[0].toInt() and 0xFF
        val length = ((payload[1].toInt() and 0xFF) shl 8) or (payload[2].toInt() and 0xFF)
        check(payload.size == 3 + length) { "malformed key update" }
        return version to payload.copyOfRange(3, payload.size)
    }

    // RENAME — [pub:32][nameLen:1][name].

    fun encodeRename(
        publicKey: ByteArray,
        name: String,
    ): ByteArray {
        check(publicKey.size == 32) { "member key must be 32 bytes" }
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        check(nameBytes.size in 1..255) { "member name length out of range" }
        return publicKey + nameBytes.size.toByte() + nameBytes
    }

    fun decodeRename(payload: ByteArray): Pair<ByteArray, String> {
        check(payload.size >= 33) { "rename too short" }
        val key = payload.copyOfRange(0, 32)
        val nameLen = payload[32].toInt() and 0xFF
        check(nameLen in 1..255 && payload.size == 33 + nameLen) { "malformed rename" }
        return key to payload.copyOfRange(33, payload.size).toString(Charsets.UTF_8)
    }
}
