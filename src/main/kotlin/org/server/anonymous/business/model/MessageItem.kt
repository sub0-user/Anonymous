package org.server.anonymous.business.model

enum class MessageDirection { IN, OUT }

enum class MessageStatus { SENT, DELIVERED }

data class MessageItem(
    val id: Long,
    val direction: MessageDirection,
    val body: String,
    val status: MessageStatus,
    val sentAtLabel: String,
)
