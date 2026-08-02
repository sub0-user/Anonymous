package org.server.anonymous.business

import org.server.anonymous.business.model.Contact
import org.server.anonymous.business.model.MessageItem

interface MessageService {
    fun messagesFor(contactId: Long): List<MessageItem>

    fun send(
        contactId: Long,
        body: String,
    ): OpResult<MessageItem>

    /**
     * Exchanges keys with a contact via one session handshake so they can be invited to a room
     * without any prior conversation. Binds and returns the peer key; no message is stored.
     * The default is a safe no-op for fakes that cannot talk to a real peer.
     */
    fun probePeerKey(contact: Contact): OpResult<ByteArray> = OpResult.Failure("Key exchange not supported")

    /** Notified for every new message and every status change (on background threads). */
    fun addMessageListener(listener: (MessageItem) -> Unit) = Unit

    /** Deletes one conversation's history from memory and from at-rest storage. */
    fun clearHistory(contactId: Long) = Unit

    fun stop() = Unit
}
