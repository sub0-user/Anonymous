package org.server.anonymous.business

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.server.anonymous.business.model.MessageDirection
import org.server.anonymous.business.model.MessageItem
import org.server.anonymous.business.model.MessageStatus

class InMemoryMessageServiceTest {
    @Test
    fun `returns the seeded thread for contact 1`() {
        assertEquals(3, InMemoryMessageService().messagesFor(1).size)
    }

    @Test
    fun `unknown contact yields an empty list`() {
        assertEquals(emptyList<MessageItem>(), InMemoryMessageService().messagesFor(999))
    }

    @Test
    fun `send appends an outbound message`() {
        val service = InMemoryMessageService()
        val result = service.send(1, "hello")
        assertTrue(result is OpResult.Success<MessageItem>)
        val sent = (result as OpResult.Success<MessageItem>).value
        assertEquals(MessageDirection.OUT, sent.direction)
        assertEquals(MessageStatus.SENT, sent.status)
        assertEquals(4, service.messagesFor(1).size)
    }

    @Test
    fun `send rejects a blank body`() {
        val service = InMemoryMessageService()
        assertTrue(service.send(1, "   ") is OpResult.Failure)
    }
}
