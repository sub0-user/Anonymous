package org.server.anonymous.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.server.anonymous.business.Identity
import org.server.anonymous.business.IdentityKeys
import org.server.anonymous.business.InMemoryMessageService
import org.server.anonymous.business.OnionAddress
import org.server.anonymous.business.OpResult
import org.server.anonymous.business.RoomMessenger
import org.server.anonymous.business.RoomStore
import org.server.anonymous.business.model.Contact
import org.server.anonymous.business.model.RoomMember
import org.server.anonymous.business.model.RoomRecord
import org.server.anonymous.business.model.RoomType
import org.server.anonymous.ui.JavaFxTestSupport
import java.nio.file.Files
import java.time.Instant

class RoomChatViewModelTest {
    private val contact = Contact(1, "alice", OnionAddress("z".repeat(56) + ".onion"), "now")

    private fun messenger(): RoomMessenger =
        RoomMessenger(
            RoomStore(Files.createTempDirectory("anonymous-room-vm").also { it.toFile().deleteOnExit() }),
            { Identity(ByteArray(32) { 9 }, Instant.now()) },
            { _, _, _, _ -> true },
        )

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        error("condition not met within 5s")
    }

    @Test
    fun `sendInvite delivers the invite as a chat message to the contact`() {
        val messageService = InMemoryMessageService()
        val vm = RoomChatViewModel(messenger(), null, 0L, { listOf(contact) }, messageService)
        val result = vm.sendInvite(contact, "inv4p:abc")
        assertTrue(result is OpResult.Success)
        assertEquals("inv4p:abc", messageService.messagesFor(contact.id).last().body)
    }

    @Test
    fun `reply to a room message attaches the reference on send`() {
        JavaFxTestSupport.onFxThread {
            val store =
                RoomStore(Files.createTempDirectory("anonymous-room-vm-reply").also { it.toFile().deleteOnExit() })
            val keys = IdentityKeys.x25519KeyPairFromSeed(ByteArray(32) { 9 })
            store.save(
                RoomRecord(
                    id = 0L,
                    name = "dev den",
                    type = RoomType.PRIVATE,
                    isFounder = false,
                    founderAddress = "a".repeat(56) + ".onion",
                    founderPublicKey = keys.publicKey,
                    serviceSeed = ByteArray(0),
                    serviceAddress = "c".repeat(56) + ".onion",
                    roomKey = ByteArray(32) { 3 },
                    keyVersion = 1,
                    entryKey = null,
                    myName = "me",
                    members = listOf(RoomMember(keys.publicKey, "me")),
                ),
            )
            val messenger =
                RoomMessenger(store, { Identity(ByteArray(32) { 9 }, Instant.now()) }, { _, _, _, _ -> true })
            messenger.sendMessage(0L, "the question")
            val vm = RoomChatViewModel(messenger, null, 0L, { emptyList() })
            val target = vm.messages.first()
            vm.replyTo(target)
            assertTrue(vm.replyBarLabel.get().startsWith("Replying to You"))
            vm.draft.set("the answer")
            vm.send()
            awaitUntil { messenger.messagesFor(0L).size == 2 }
            val sent = messenger.messagesFor(0L).last()
            assertEquals("the answer", sent.body)
            assertEquals("the question", sent.replyTo!!.text)
            assertNull(vm.replyingTo.get())
        }
    }

    @Test
    fun `send clears the composer immediately and never blocks the caller`() {
        JavaFxTestSupport.onFxThread {
            val store =
                RoomStore(Files.createTempDirectory("anonymous-room-vm-send").also { it.toFile().deleteOnExit() })
            val keys = IdentityKeys.x25519KeyPairFromSeed(ByteArray(32) { 9 })
            store.save(
                RoomRecord(
                    id = 0L,
                    name = "dev den",
                    type = RoomType.PRIVATE,
                    isFounder = false,
                    founderAddress = "a".repeat(56) + ".onion",
                    founderPublicKey = keys.publicKey,
                    serviceSeed = ByteArray(0),
                    serviceAddress = "c".repeat(56) + ".onion",
                    roomKey = ByteArray(32) { 3 },
                    keyVersion = 1,
                    entryKey = null,
                    myName = "me",
                    members = listOf(RoomMember(keys.publicKey, "me")),
                ),
            )
            val messenger =
                RoomMessenger(store, { Identity(ByteArray(32) { 9 }, Instant.now()) }, { _, _, _, _ -> true })
            val vm = RoomChatViewModel(messenger, null, 0L, { emptyList() })
            vm.draft.set("hello")
            vm.send()
            // The composer clears synchronously even though delivery is queued off-thread.
            assertEquals("", vm.draft.get())
            awaitUntil { messenger.messagesFor(0L).size == 1 }
            assertEquals("hello", messenger.messagesFor(0L).single().body)
        }
    }

    @Test
    fun `sendInvite fails when no chat service is wired`() {
        val vm = RoomChatViewModel(messenger(), null, 0L, { emptyList() })
        assertTrue(vm.sendInvite(contact, "inv4p:abc") is OpResult.Failure)
    }

    @Test
    fun `contactsForInvite lists every contact, keyed or not`() {
        val keyed = Contact(2, "bob", OnionAddress("y".repeat(56) + ".onion"), "now", ByteArray(32) { 1 })
        val vm = RoomChatViewModel(messenger(), null, 0L, { listOf(contact, keyed) })
        assertEquals(listOf(contact, keyed), vm.contactsForInvite())
    }

    @Test
    fun `addMember fails cleanly when the key exchange cannot reach the contact`() {
        // InMemoryMessageService cannot reach a real peer, so the probe fails and the
        // invite must not be created (the "Only the founder can invite" branch is never reached).
        val vm = RoomChatViewModel(messenger(), null, 0L, { listOf(contact) }, InMemoryMessageService())
        val result = vm.addMember(contact, "neo", null)
        assertTrue(result is OpResult.Failure)
        assertFalse((result as OpResult.Failure).reason == "Only the founder can invite")
    }

    @Test
    fun `addMember with a cached key skips the probe`() {
        val keyed = Contact(2, "bob", OnionAddress("y".repeat(56) + ".onion"), "now", ByteArray(32) { 1 })
        val vm = RoomChatViewModel(messenger(), null, 0L, { listOf(keyed) }, InMemoryMessageService())
        val result = vm.addMember(keyed, "neo", null)
        // No roomHost here, so this proves the probe was skipped (a probe would have
        // failed with "Key exchange not supported" first).
        assertEquals("Only the founder can invite", (result as OpResult.Failure).reason)
    }
}
