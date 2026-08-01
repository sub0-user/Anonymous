package org.server.anonymous.business

import org.server.anonymous.business.model.RoomRecord

/** Display-name rules for rooms: normalized, 1-32 chars, unique per room (case-insensitive). */
object RoomNames {
    const val MAX_LENGTH = 32

    fun normalize(name: String): String = name.trim().take(MAX_LENGTH)

    fun isValid(name: String): Boolean = normalize(name).isNotEmpty()

    fun isUnique(
        room: RoomRecord,
        proposed: String,
    ): Boolean = room.members.none { it.name.equals(proposed.trim(), ignoreCase = true) }
}
