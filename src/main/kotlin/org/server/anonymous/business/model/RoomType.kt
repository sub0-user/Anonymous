package org.server.anonymous.business.model

/** Private rooms are gated by Tor client auth; public rooms by a shared entry key. */
enum class RoomType {
    PRIVATE,
    PUBLIC,
}
