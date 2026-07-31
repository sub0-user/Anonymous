package org.server.anonymous.business

import org.server.anonymous.business.model.MessageItem

interface MessageService {
    fun messagesFor(contactId: Long): List<MessageItem>

    fun send(
        contactId: Long,
        body: String,
    ): OpResult<MessageItem>
}
