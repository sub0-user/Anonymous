package org.server.anonymous.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.server.anonymous.business.InMemoryMessageService
import org.server.anonymous.business.OnionAddress
import org.server.anonymous.business.model.Contact

class ChatViewModelTest {
    private val contact = Contact(1, "raven", OnionAddress("z".repeat(56) + ".onion"), "2m ago")

    @Test
    fun `title and subtitle reflect the contact`() {
        val vm = ChatViewModel(InMemoryMessageService(), contact)
        assertEquals("raven", vm.title.get())
        assertEquals("● online · E2E encrypted", vm.subtitle.get())
    }

    @Test
    fun `loads the seeded messages`() {
        val vm = ChatViewModel(InMemoryMessageService(), contact)
        assertEquals(3, vm.messages.size)
    }

    @Test
    fun `send appends the message and clears the draft`() {
        val vm = ChatViewModel(InMemoryMessageService(), contact)
        vm.draft.set("hello")
        vm.send()
        assertEquals(4, vm.messages.size)
        assertEquals("", vm.draft.get())
        assertNull(vm.sendFeedback.get())
    }

    @Test
    fun `blank draft does not send`() {
        val vm = ChatViewModel(InMemoryMessageService(), contact)
        vm.draft.set("   ")
        vm.send()
        assertEquals(3, vm.messages.size)
    }
}
