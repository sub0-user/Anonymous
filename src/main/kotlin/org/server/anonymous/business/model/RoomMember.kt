package org.server.anonymous.business.model

/**
 * One member of a room. The public key is the member's static X25519 key (their identity
 * in the room); the name is their display name, unique in the room and admin-overridable.
 * The address is their identity onion service (needed for outbound control delivery).
 * Members carry the room key wrapped for them (private rooms wrap it at invite time,
 * public rooms on join). Membership is enforced at the application layer — the room
 * service itself has no transport client auth.
 */
data class RoomMember(
    val publicKey: ByteArray,
    val name: String,
    val status: MemberStatus = MemberStatus.MEMBER,
    val wrappedRoomKey: ByteArray? = null,
    /** The member's identity address; null only for the founder's own entry. */
    val address: String? = null,
    /** Pending-invite expiry (epoch seconds); null = no expiry. Enforced by the founder. */
    val inviteExpiryEpochSeconds: Long? = null,
)
