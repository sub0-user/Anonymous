package org.server.anonymous.business.model

/**
 * A room as persisted locally. `isFounder` selects the role: the founder hosts the room's
 * onion service and is admin; members hold the room key (to decrypt) and their own member
 * record. The room key and member auth halves are stored in plaintext in the 0600 data
 * directory, same trust model as `identity.properties` — the store is local to the node.
 */
data class RoomRecord(
    val id: Long,
    val name: String,
    val type: RoomType,
    val isFounder: Boolean,
    /** The founder's identity address; null when we are the founder. */
    val founderAddress: String?,
    /** Ed25519 seed for the room's onion service (persisted so the room URL is stable). */
    val serviceSeed: ByteArray,
    val serviceAddress: String,
    /** The room key used to encrypt room messages; version bumps on every rotation. */
    val roomKey: ByteArray,
    val keyVersion: Int,
    /** Public rooms only: the shared door key published with the room URL. */
    val entryKey: String?,
    /** Our display name in this room. */
    val myName: String,
    val members: List<RoomMember>,
)
