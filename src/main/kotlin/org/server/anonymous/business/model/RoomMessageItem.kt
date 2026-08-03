package org.server.anonymous.business.model

/** A message exchanged inside a room, addressed by sender key (resolved to a name in the UI). */
data class RoomMessageItem(
    val id: Long,
    val roomId: Long,
    val senderPublicKey: ByteArray,
    val body: String,
    val timeLabel: String,
    val isOutgoing: Boolean,
    /** The message this one replies to, if any. */
    val replyTo: ReplyRef? = null,
)
