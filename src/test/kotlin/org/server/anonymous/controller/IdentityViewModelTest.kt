package org.server.anonymous.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.server.anonymous.business.ContactBook
import org.server.anonymous.business.IdentityService
import org.server.anonymous.business.NodeStatus
import org.server.anonymous.business.OpResult
import org.server.anonymous.business.RoomStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class IdentityViewModelTest {
    private fun tempIdentity(): IdentityService {
        val dir: Path = Files.createTempDirectory("anonymous-identity-vm").also { it.toFile().deleteOnExit() }
        return IdentityService(dir)
    }

    private fun vm(
        identity: IdentityService = tempIdentity(),
        contacts: ContactBook = ContactBook(),
        rooms: RoomStore = RoomStore(Files.createTempDirectory("anon-vm-rooms").also { it.toFile().deleteOnExit() }),
    ): IdentityViewModel = IdentityViewModel(FakeNodeStatusSource(), identity, contacts, rooms)

    @Test
    fun `shows a placeholder until the node is online`() {
        val viewModel = vm()
        assertEquals("starting…", viewModel.onionAddress.get())
    }

    @Test
    fun `offline status is honest`() {
        val source = FakeNodeStatusSource()
        val viewModel = IdentityViewModel(source, tempIdentity(), ContactBook(), RoomStore(tempDir()))
        viewModel.applyStatus(NodeStatus.Offline("tor not reachable"))
        assertEquals("—", viewModel.onionAddress.get())
        assertEquals("node offline: tor not reachable", viewModel.nodeStatus.get())
    }

    @Test
    fun `bootstrapping shows progress`() {
        val viewModel = vm()
        viewModel.applyStatus(NodeStatus.Bootstrapping(60))
        assertEquals("bootstrapping 60%…", viewModel.nodeStatus.get())
    }

    @Test
    fun `online shows the real address`() {
        val viewModel = vm()
        viewModel.applyStatus(NodeStatus.Online("a".repeat(56) + ".onion", 9050))
        assertEquals("a".repeat(56) + ".onion", viewModel.onionAddress.get())
        assertEquals("Node online — receiving messages", viewModel.nodeStatus.get())
    }

    @Test
    fun `listener wiring updates properties on the fx thread`() {
        org.server.anonymous.ui.JavaFxTestSupport
            .init()
        val source = FakeNodeStatusSource()
        val viewModel = IdentityViewModel(source, tempIdentity(), ContactBook(), RoomStore(tempDir()))
        source.emit(NodeStatus.Online("b".repeat(56) + ".onion", 9050))
        // Platform.runLater is async; poll (off the FX thread) for the update.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline && viewModel.onionAddress.get() != "b".repeat(56) + ".onion") {
            Thread.sleep(20)
        }
        assertEquals("b".repeat(56) + ".onion", viewModel.onionAddress.get())
    }

    @Test
    fun `export and import roundtrip restores seed contacts and rooms`() {
        val identity = tempIdentity()
        val seed = identity.getOrCreate().seed
        val contacts = ContactBook()
        val contact = (contacts.addContact("alice", "a".repeat(56) + ".onion") as OpResult.Success).value
        contacts.bindPeerKey(contact.id, ByteArray(32) { 7 })
        contacts.block("b".repeat(56) + ".onion")
        val rooms = RoomStore(tempDir())
        val vm1 = IdentityViewModel(FakeNodeStatusSource(), identity, contacts, rooms)

        val exported = vm1.exportIdentity("hunter2".toCharArray())
        assertTrue(exported is OpResult.Success)

        // Restore into a fresh profile: seed, contacts (with key bindings), blocks and rooms.
        val restoredIdentity = tempIdentity()
        val restoredContacts = ContactBook()
        val restoredRooms = RoomStore(tempDir())
        val vm2 = IdentityViewModel(FakeNodeStatusSource(), restoredIdentity, restoredContacts, restoredRooms)
        val restored = vm2.importIdentity((exported as OpResult.Success).value, "hunter2".toCharArray())
        assertTrue(restored is OpResult.Success)

        assertTrue(restoredIdentity.getOrCreate().seed.contentEquals(seed))
        val restoredContact = restoredContacts.listContacts().single()
        assertEquals("alice", restoredContact.alias)
        assertTrue(restoredContact.peerPublicKey!!.contentEquals(ByteArray(32) { 7 }))
        assertTrue(restoredContacts.isBlocked("b".repeat(56) + ".onion"))
    }

    @Test
    fun `import with a wrong passphrase fails`() {
        val viewModel = vm()
        val exported = viewModel.exportIdentity("right".toCharArray()) as OpResult.Success
        val restored = viewModel.importIdentity(exported.value, "wrong".toCharArray())
        assertTrue(restored is OpResult.Failure)
    }

    private fun tempDir(): Path = Files.createTempDirectory("anon-vm-rooms").also { it.toFile().deleteOnExit() }
}
