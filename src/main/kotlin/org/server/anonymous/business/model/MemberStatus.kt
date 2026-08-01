package org.server.anonymous.business.model

/**
 * A member's standing in a room. KICKED members no longer hold a client-auth entry
 * (private) and no longer receive key rotations; INVITED members have a pending invite.
 */
enum class MemberStatus {
    MEMBER,
    INVITED,
    KICKED,
}
