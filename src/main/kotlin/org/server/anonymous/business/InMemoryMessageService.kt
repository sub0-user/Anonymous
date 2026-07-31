package org.server.anonymous.business

import org.server.anonymous.business.model.MessageDirection
import org.server.anonymous.business.model.MessageItem
import org.server.anonymous.business.model.MessageStatus

/** Mock message store — replaced by persistence + P2P delivery in Phases 3-4. */
class InMemoryMessageService : MessageService {
    private val store = mutableMapOf<Long, MutableList<MessageItem>>()
    private var nextId = 1L

    init {
        store[1] =
            mutableListOf(
                MessageItem(
                    nextId++,
                    MessageDirection.OUT,
                    "did you read the protocol doc?",
                    MessageStatus.DELIVERED,
                    "09:41",
                ),
                MessageItem(
                    nextId++,
                    MessageDirection.IN,
                    "just finished. the handshake is clean",
                    MessageStatus.DELIVERED,
                    "09:42",
                ),
                MessageItem(nextId++, MessageDirection.OUT, "ship it", MessageStatus.DELIVERED, "09:43"),
            )
    }

    override fun messagesFor(contactId: Long): List<MessageItem> = store[contactId]?.toList() ?: emptyList()

    override fun send(
        contactId: Long,
        body: String,
    ): OpResult<MessageItem> {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return OpResult.Failure("Message is empty")
        val message = MessageItem(nextId++, MessageDirection.OUT, trimmed, MessageStatus.SENT, "now")
        store.getOrPut(contactId) { mutableListOf() } += message
        return OpResult.Success(message)
    }
}
