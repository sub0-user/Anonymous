package org.server.anonymous.business

import java.util.Base64

/**
 * Room invites — the opaque strings carried by 1:1 chat messages that let an invited
 * contact join a room.
 * - Private: the member's wrapped room key (membership is enforced at the app layer; the
 *   room service itself has no transport client auth).
 * - Public: the shared entry key; the wrapped room key is fetched from the founder at join.
 * Private invites are single-use by default and may carry an expiry epoch (host-enforced).
 */
sealed interface RoomInvite {
    val roomId: Long

    /** The room's onion service address (founder-hosted). */
    val serviceAddress: String

    /** The founder's identity address (used to reach them for re-sync). */
    val founderAddress: String

    /** The founder's static X25519 key — needed to unwrap room keys. */
    val founderPublicKey: ByteArray
}

data class PrivateRoomInvite(
    override val roomId: Long,
    override val serviceAddress: String,
    override val founderAddress: String,
    val wrappedRoomKey: ByteArray,
    override val founderPublicKey: ByteArray,
    val expiryEpochSeconds: Long? = null,
) : RoomInvite

data class PublicRoomInvite(
    override val roomId: Long,
    override val serviceAddress: String,
    override val founderAddress: String,
    val entryKey: String,
    override val founderPublicKey: ByteArray,
) : RoomInvite

/**
 * @Suppress TooManyFunctions: one codec over a small fixed grammar; splitting encode/decode
 * of the two invite kinds would fragment the format definition.
 */
@Suppress("TooManyFunctions")
object InviteCodec {
    private const val PRIVATE_PREFIX = "inv4p"
    private const val PUBLIC_PREFIX = "inv4u"
    private const val VERSION = "v1"
    private val urlEncoder = Base64.getUrlEncoder().withoutPadding()
    private val urlDecoder = Base64.getUrlDecoder()

    /** Throws [IllegalStateException] on any malformed invite — fail-fast on the founder side too. */
    fun encode(invite: RoomInvite): String {
        validate(invite)
        return when (invite) {
            is PrivateRoomInvite -> encodePrivate(invite)
            is PublicRoomInvite -> encodePublic(invite)
        }
    }

    /** Throws [IllegalStateException] on any malformed or tampered invite. */
    fun decode(text: String): RoomInvite {
        val parts = text.trim().split(":")
        return when {
            parts.size == 7 && parts[0] == PRIVATE_PREFIX && parts[1] == VERSION -> decodePrivate(parts)
            parts.size == 8 && parts[0] == PRIVATE_PREFIX && parts[1] == VERSION -> decodePrivate(parts)
            parts.size == 7 && parts[0] == PUBLIC_PREFIX && parts[1] == VERSION -> decodePublic(parts)
            else -> invalid()
        }
    }

    private fun validate(invite: RoomInvite) {
        checkValidAddresses(invite.serviceAddress, invite.founderAddress)
        check(invite.founderPublicKey.size == 32) { "invalid founder key" }
        when (invite) {
            is PrivateRoomInvite ->
                check(invite.wrappedRoomKey.size > RoomKeyWrap.NONCE_LENGTH) { "invalid wrapped room key" }
            is PublicRoomInvite -> check(EntryKey.isValid(invite.entryKey)) { "invalid entry key" }
        }
    }

    private fun encodePrivate(invite: PrivateRoomInvite): String {
        val base =
            listOf(
                PRIVATE_PREFIX,
                VERSION,
                invite.serviceAddress,
                urlEncoder.encodeToString(invite.wrappedRoomKey),
                roomIdHex(invite.roomId),
                invite.founderAddress,
                urlEncoder.encodeToString(invite.founderPublicKey),
            )
        return (if (invite.expiryEpochSeconds != null) base + invite.expiryEpochSeconds.toString() else base)
            .joinToString(":")
    }

    private fun encodePublic(invite: PublicRoomInvite): String =
        listOf(
            PUBLIC_PREFIX,
            VERSION,
            invite.serviceAddress,
            roomIdHex(invite.roomId),
            invite.entryKey,
            invite.founderAddress,
            urlEncoder.encodeToString(invite.founderPublicKey),
        ).joinToString(":")

    private fun decodePrivate(parts: List<String>): PrivateRoomInvite {
        val serviceAddress = parts[2]
        val wrapped = decodeUrl(parts[3])
        val roomId = parseRoomId(parts[4])
        val founderAddress = parts[5]
        val founderPublicKey = decodeUrl(parts[6])
        val expiry = parts.getOrNull(7)?.toLongOrNull() ?: if (parts.size == 8) invalid() else null
        check(wrapped.size > RoomKeyWrap.NONCE_LENGTH) { "invalid wrapped room key" }
        check(founderPublicKey.size == 32) { "invalid founder key" }
        checkValidAddresses(serviceAddress, founderAddress)
        return PrivateRoomInvite(roomId, serviceAddress, founderAddress, wrapped, founderPublicKey, expiry)
    }

    private fun decodePublic(parts: List<String>): PublicRoomInvite {
        val serviceAddress = parts[2]
        val roomId = parseRoomId(parts[3])
        val entryKey = parts[4]
        val founderAddress = parts[5]
        val founderPublicKey = decodeUrl(parts[6])
        check(EntryKey.isValid(entryKey)) { "invalid entry key" }
        check(founderPublicKey.size == 32) { "invalid founder key" }
        checkValidAddresses(serviceAddress, founderAddress)
        return PublicRoomInvite(roomId, serviceAddress, founderAddress, entryKey, founderPublicKey)
    }

    private fun decodeUrl(text: String): ByteArray {
        val decoded = runCatching { urlDecoder.decode(text) }.getOrNull() ?: invalid()
        return decoded
    }

    private fun parseRoomId(hex: String): Long {
        check(hex.length == 16) { "invalid room id" }
        return runCatching { java.lang.Long.parseUnsignedLong(hex, 16) }.getOrNull() ?: invalid()
    }

    private fun checkValidAddresses(
        serviceAddress: String,
        founderAddress: String,
    ) {
        check(OnionAddressValidator.isValid(serviceAddress)) { "invalid room address" }
        check(OnionAddressValidator.isValid(founderAddress)) { "invalid founder address" }
    }

    private fun roomIdHex(roomId: Long): String = "%016x".format(roomId)

    private fun invalid(): Nothing = error("invalid invite")
}
