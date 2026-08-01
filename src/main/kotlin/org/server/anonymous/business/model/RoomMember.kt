package org.server.anonymous.business.model

/**
 * One member of a room. The public key is the member's static X25519 key (their identity
 * in the room); the name is their display name, unique in the room and admin-overridable.
 * Private-room members also carry the founder-generated client-auth private half and the
 * room key wrapped for them; public-room members get the wrapped key on join instead.
 */
data class RoomMember(
    val publicKey: ByteArray,
    val name: String,
    val status: MemberStatus = MemberStatus.MEMBER,
    val clientAuthPrivate: ByteArray? = null,
    val wrappedRoomKey: ByteArray? = null,
)
