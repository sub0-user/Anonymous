package org.server.anonymous.business

/**
 * Room wire formats (Phase 4). A room message rides a `CONTENT_ROOM_MSG` frame whose body is
 * `[roomId:8][keyVersion:1][nonce:12][ciphertext]` — the ciphertext is the room-key AEAD with
 * AAD binding room id + key version, so retargeting a message to another room fails the tag.
 * Control frames are `CONTENT_ROOM_CONTROL` with `[op:1][roomId:8][payload]`.
 */
object RoomEnvelope {
    const val ROOM_ID_LENGTH = 8
    const val AAD = "anonymous:room:v1"

    data class RoomMessage(
        val roomId: Long,
        val keyVersion: Int,
        val nonce: ByteArray,
        val ciphertext: ByteArray,
    )

    fun encodeRoomMessage(message: RoomMessage): ByteArray =
        roomIdToBytes(message.roomId) +
            message.keyVersion.toByte() +
            message.nonce +
            message.ciphertext

    fun decodeRoomMessage(body: ByteArray): RoomMessage {
        check(body.size >= ROOM_ID_LENGTH + 1 + SessionCrypto.NONCE_LENGTH) { "room message too short" }
        val roomId = roomIdFromBytes(body.copyOfRange(0, ROOM_ID_LENGTH))
        val version = body[ROOM_ID_LENGTH].toInt() and 0xFF
        val nonce = body.copyOfRange(ROOM_ID_LENGTH + 1, ROOM_ID_LENGTH + 1 + SessionCrypto.NONCE_LENGTH)
        val ciphertext = body.copyOfRange(ROOM_ID_LENGTH + 1 + SessionCrypto.NONCE_LENGTH, body.size)
        return RoomMessage(roomId, version, nonce, ciphertext)
    }

    /** AAD for room-key AEAD: protocol tag + room id + key version. */
    fun roomAad(
        roomId: Long,
        keyVersion: Int,
    ): ByteArray = AAD.toByteArray(Charsets.UTF_8) + roomIdToBytes(roomId) + keyVersion.toByte()

    fun roomIdToBytes(roomId: Long): ByteArray =
        byteArrayOf(
            (roomId ushr 56).toByte(),
            (roomId ushr 48).toByte(),
            (roomId ushr 40).toByte(),
            (roomId ushr 32).toByte(),
            (roomId ushr 24).toByte(),
            (roomId ushr 16).toByte(),
            (roomId ushr 8).toByte(),
            roomId.toByte(),
        )

    fun roomIdFromBytes(bytes: ByteArray): Long {
        check(bytes.size == ROOM_ID_LENGTH) { "room id must be $ROOM_ID_LENGTH bytes" }
        var value = 0L
        for (b in bytes) value = (value shl 8) or (b.toLong() and 0xFF)
        return value
    }
}
