package org.server.anonymous.business

import java.util.Base64

/**
 * Room invites (Phase 4) — the opaque copy-pasteable strings that carry entry credentials.
 * - Private: the client-auth private half (transport entry) + the member's wrapped room key.
 * - Public: the shared entry key; the wrapped room key is fetched from the founder at join.
 * Private invites are single-use by default and may carry an expiry epoch (host-enforced).
 */
sealed interface RoomInvite {
    val roomId: Long

    /** The room's onion service address (founder-hosted). */
    val serviceAddress: String

    /** The founder's identity address (used to reach them for re-sync). */
    val founderAddress: String
}

data class PrivateRoomInvite(
    override val roomId: Long,
    override val serviceAddress: String,
    override val founderAddress: String,
    val clientAuthPrivate: ByteArray,
    val wrappedRoomKey: ByteArray,
    val expiryEpochSeconds: Long? = null,
) : RoomInvite

data class PublicRoomInvite(
    override val roomId: Long,
    override val serviceAddress: String,
    override val founderAddress: String,
    val entryKey: String,
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
            parts.size == 6 && parts[0] == PUBLIC_PREFIX && parts[1] == VERSION -> decodePublic(parts)
            else -> invalid()
        }
    }

    private fun validate(invite: RoomInvite) {
        checkValidAddresses(invite.serviceAddress, invite.founderAddress)
        when (invite) {
            is PrivateRoomInvite -> {
                check(invite.clientAuthPrivate.size == 32) { "invalid client-auth key length" }
                check(invite.wrappedRoomKey.size > RoomKeyWrap.NONCE_LENGTH) { "invalid wrapped room key" }
            }
            is PublicRoomInvite -> check(EntryKey.isValid(invite.entryKey)) { "invalid entry key" }
        }
    }

    private fun encodePrivate(invite: PrivateRoomInvite): String {
        val base =
            listOf(
                PRIVATE_PREFIX,
                VERSION,
                invite.serviceAddress,
                urlEncoder.encodeToString(invite.clientAuthPrivate),
                urlEncoder.encodeToString(invite.wrappedRoomKey),
                roomIdHex(invite.roomId),
                invite.founderAddress,
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
        ).joinToString(":")

    private fun decodePrivate(parts: List<String>): PrivateRoomInvite {
        val serviceAddress = parts[2]
        val authPrivate = decodeUrl(parts[3])
        val wrapped = decodeUrl(parts[4])
        val roomId = parseRoomId(parts[5])
        val founderAddress = parts[6]
        val expiry = parts.getOrNull(7)?.toLongOrNull() ?: if (parts.size == 8) invalid() else null
        check(authPrivate.size == 32) { "invalid client-auth key length" }
        check(wrapped.size > RoomKeyWrap.NONCE_LENGTH) { "invalid wrapped room key" }
        checkValidAddresses(serviceAddress, founderAddress)
        return PrivateRoomInvite(roomId, serviceAddress, founderAddress, authPrivate, wrapped, expiry)
    }

    private fun decodePublic(parts: List<String>): PublicRoomInvite {
        val serviceAddress = parts[2]
        val roomId = parseRoomId(parts[3])
        val entryKey = parts[4]
        val founderAddress = parts[5]
        check(EntryKey.isValid(entryKey)) { "invalid entry key" }
        checkValidAddresses(serviceAddress, founderAddress)
        return PublicRoomInvite(roomId, serviceAddress, founderAddress, entryKey)
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
