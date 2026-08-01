package org.server.anonymous.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.server.anonymous.business.IdentityService
import org.server.anonymous.business.NodeStatus
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class IdentityViewModelTest {
    private fun tempIdentity(): IdentityService {
        val dir: Path = Files.createTempDirectory("anonymous-identity-vm").also { it.toFile().deleteOnExit() }
        return IdentityService(dir)
    }

    @Test
    fun `shows a placeholder until the node is online`() {
        val vm = IdentityViewModel(FakeNodeStatusSource(), tempIdentity())
        assertEquals("starting…", vm.onionAddress.get())
    }

    @Test
    fun `offline status is honest`() {
        val source = FakeNodeStatusSource()
        val vm = IdentityViewModel(source, tempIdentity())
        vm.applyStatus(NodeStatus.Offline("tor not reachable"))
        assertEquals("—", vm.onionAddress.get())
        assertEquals("node offline: tor not reachable", vm.nodeStatus.get())
    }

    @Test
    fun `bootstrapping shows progress`() {
        val vm = IdentityViewModel(FakeNodeStatusSource(), tempIdentity())
        vm.applyStatus(NodeStatus.Bootstrapping(60))
        assertEquals("bootstrapping 60%…", vm.nodeStatus.get())
    }

    @Test
    fun `online shows the real address`() {
        val vm = IdentityViewModel(FakeNodeStatusSource(), tempIdentity())
        vm.applyStatus(NodeStatus.Online("a".repeat(56) + ".onion", 9050))
        assertEquals("a".repeat(56) + ".onion", vm.onionAddress.get())
        assertEquals("Node online — receiving messages", vm.nodeStatus.get())
    }

    @Test
    fun `listener wiring updates properties on the fx thread`() {
        org.server.anonymous.ui.JavaFxTestSupport
            .init()
        val source = FakeNodeStatusSource()
        val vm = IdentityViewModel(source, tempIdentity())
        source.emit(NodeStatus.Online("b".repeat(56) + ".onion", 9050))
        // Platform.runLater is async; poll (off the FX thread) for the update.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline && vm.onionAddress.get() != "b".repeat(56) + ".onion") {
            Thread.sleep(20)
        }
        assertEquals("b".repeat(56) + ".onion", vm.onionAddress.get())
    }

    @Test
    fun `export and import roundtrip restores the same seed`() {
        val vm = IdentityViewModel(FakeNodeStatusSource(), tempIdentity())
        val exported = vm.exportIdentity("hunter2".toCharArray())
        assertTrue(exported is org.server.anonymous.business.OpResult.Success)
        val restored =
            vm.importIdentity(
                (exported as org.server.anonymous.business.OpResult.Success).value,
                "hunter2".toCharArray(),
            )
        assertTrue(restored is org.server.anonymous.business.OpResult.Success)
    }

    @Test
    fun `import with a wrong passphrase fails`() {
        val vm = IdentityViewModel(FakeNodeStatusSource(), tempIdentity())
        val exported = vm.exportIdentity("right".toCharArray()) as org.server.anonymous.business.OpResult.Success
        val restored = vm.importIdentity(exported.value, "wrong".toCharArray())
        assertTrue(restored is org.server.anonymous.business.OpResult.Failure)
    }
}
