package org.server.anonymous.business

/**
 * Room-key wrapping (Phase 4): a per-member wrap key is derived from ECDH between the
 * founder's static key and the member's static key, salted by the room id, and the room
 * key is AEAD-wrapped with it. Kicking = rotating the room key and re-wrapping for the
 * remaining members, so a removed member holds a wrap key for a key that no longer exists.
 */
object RoomKeyWrap {
    const val ROOM_KEY_LENGTH = 32
    const val NONCE_LENGTH = SessionCrypto.NONCE_LENGTH
    private const val INFO = "anonymous:room-wrap:v1"
    private const val AAD = "anonymous:room-wrap:v1"

    fun newRoomKey(): ByteArray = SessionCrypto.randomBytes(ROOM_KEY_LENGTH)

    /**
     * The per-member wrap key. Both the founder and the member can derive it (each has one
     * half of the ECDH pair), but the founder always creates the wrapped copies.
     */
    fun wrapKey(
        myPrivate: ByteArray,
        peerPublic: ByteArray,
        roomId: Long,
    ): ByteArray =
        SessionCrypto.hkdf(
            IdentityKeys.sharedSecret(myPrivate, peerPublic),
            RoomEnvelope.roomIdToBytes(roomId),
            INFO.toByteArray(Charsets.UTF_8),
            ROOM_KEY_LENGTH,
        )

    /** Wrapped form: 12-byte nonce || AEAD tag+ciphertext. */
    fun wrap(
        roomKey: ByteArray,
        wrapKey: ByteArray,
        roomId: Long,
    ): ByteArray {
        val nonce = SessionCrypto.randomNonce()
        return nonce + SessionCrypto.encrypt(wrapKey, nonce, roomKey, aad(roomId))
    }

    /** Throws [javax.crypto.AEADBadTagException] on a tampered wrap. */
    fun unwrap(
        wrapped: ByteArray,
        wrapKey: ByteArray,
        roomId: Long,
    ): ByteArray {
        check(wrapped.size > NONCE_LENGTH) { "wrapped room key too short" }
        val nonce = wrapped.copyOfRange(0, NONCE_LENGTH)
        val ciphertext = wrapped.copyOfRange(NONCE_LENGTH, wrapped.size)
        return SessionCrypto.decrypt(wrapKey, nonce, ciphertext, aad(roomId))
    }

    private fun aad(roomId: Long): ByteArray = AAD.toByteArray(Charsets.UTF_8) + RoomEnvelope.roomIdToBytes(roomId)
}
