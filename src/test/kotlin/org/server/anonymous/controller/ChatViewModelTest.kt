package org.server.anonymous.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.server.anonymous.business.IdentityService
import org.server.anonymous.business.InMemoryContactService
import org.server.anonymous.business.InMemoryMessageService
import org.server.anonymous.business.NodeStatus
import org.server.anonymous.business.OnionAddress
import org.server.anonymous.business.model.Contact
import org.server.anonymous.business.model.MessageDirection
import java.nio.file.Files
import java.nio.file.Path

class ChatViewModelTest {
    private val contact = Contact(1, "raven", OnionAddress("z".repeat(56) + ".onion"), "2m ago")

    private fun tempIdentity(): IdentityService {
        val dir: Path = Files.createTempDirectory("anonymous-chat-vm").also { it.toFile().deleteOnExit() }
        return IdentityService(dir)
    }

    private fun viewModel(): ChatViewModel {
        val source = FakeNodeStatusSource()
        source.current = NodeStatus.Online("a".repeat(56) + ".onion", 9050)
        return ChatViewModel(
            InMemoryMessageService(),
            InMemoryContactService(),
            source,
            tempIdentity(),
            contact,
        )
    }

    @Test
    fun `title reflects the contact`() {
        assertEquals("raven", viewModel().title.get())
    }

    @Test
    fun `loads the seeded messages`() {
        assertEquals(3, viewModel().messages.size)
    }

    @Test
    fun `send appends the message and clears the draft`() {
        val vm = viewModel()
        vm.draft.set("hello")
        vm.send()
        assertEquals(4, vm.messages.size)
        assertEquals("", vm.draft.get())
        assertNull(vm.sendFeedback.get())
    }

    @Test
    fun `reply to a message shows the bar and attaches the reference on send`() {
        val vm = viewModel()
        val target = vm.messages.first { it.direction == MessageDirection.IN }
        vm.replyTo(target)
        assertTrue(vm.replyingTo.get() == target)
        assertTrue(vm.replyBarLabel.get().startsWith("Replying to raven"))
        vm.draft.set("got it")
        vm.send()
        val sent = vm.messages.last()
        assertNotNull(sent.replyTo)
        assertEquals(target.body, sent.replyTo!!.text)
        assertEquals("raven", sent.replyTo!!.senderName)
        assertNull(vm.replyingTo.get())
        assertEquals("", vm.replyBarLabel.get())
    }

    @Test
    fun `reply to an own message names you and clears on dismiss`() {
        val vm = viewModel()
        val mine = vm.messages.first { it.direction == MessageDirection.OUT }
        vm.replyTo(mine)
        assertTrue(vm.replyBarLabel.get().startsWith("Replying to You"))
        vm.clearReply()
        assertNull(vm.replyingTo.get())
    }

    @Test
    fun `blank draft does not send`() {
        val vm = viewModel()
        vm.draft.set("   ")
        vm.send()
        assertEquals(3, vm.messages.size)
    }

    @Test
    fun `block toggles the contact's blocked state`() {
        val vm = viewModel()
        assertFalse(vm.blocked.get())
        vm.toggleBlocked()
        assertTrue(vm.blocked.get())
        vm.toggleBlocked()
        assertFalse(vm.blocked.get())
    }

    @Test
    fun `delete removes the contact`() {
        val vm = viewModel()
        assertTrue(vm.deleteContact())
        assertFalse(vm.deleteContact())
    }
}
