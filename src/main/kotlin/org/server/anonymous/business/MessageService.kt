package org.server.anonymous.business

import org.server.anonymous.business.model.MessageItem

interface MessageService {
    fun messagesFor(contactId: Long): List<MessageItem>

    fun send(
        contactId: Long,
        body: String,
    ): OpResult<MessageItem>

    /** Notified for every new message and every status change (on background threads). */
    fun addMessageListener(listener: (MessageItem) -> Unit) = Unit

    /** Deletes one conversation's history from memory and from at-rest storage. */
    fun clearHistory(contactId: Long) = Unit

    fun stop() = Unit
}
