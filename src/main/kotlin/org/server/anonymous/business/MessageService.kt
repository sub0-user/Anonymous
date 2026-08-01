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

    fun stop() = Unit
}
