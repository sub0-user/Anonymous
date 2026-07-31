package org.server.anonymous.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.server.anonymous.business.NodeStatus
import java.util.concurrent.TimeUnit

class IdentityViewModelTest {
    @Test
    fun `shows a placeholder until the node is online`() {
        val vm = IdentityViewModel(FakeNodeStatusSource())
        assertEquals("starting…", vm.onionAddress.get())
    }

    @Test
    fun `offline status is honest`() {
        val source = FakeNodeStatusSource()
        val vm = IdentityViewModel(source)
        vm.applyStatus(NodeStatus.Offline("tor not reachable"))
        assertEquals("—", vm.onionAddress.get())
        assertEquals("node offline: tor not reachable", vm.nodeStatus.get())
    }

    @Test
    fun `bootstrapping shows progress`() {
        val vm = IdentityViewModel(FakeNodeStatusSource())
        vm.applyStatus(NodeStatus.Bootstrapping(60))
        assertEquals("bootstrapping 60%…", vm.nodeStatus.get())
    }

    @Test
    fun `online shows the real address`() {
        val vm = IdentityViewModel(FakeNodeStatusSource())
        vm.applyStatus(NodeStatus.Online("a".repeat(56) + ".onion"))
        assertEquals("a".repeat(56) + ".onion", vm.onionAddress.get())
        assertEquals("Node online — receiving messages", vm.nodeStatus.get())
    }

    @Test
    fun `listener wiring updates properties on the fx thread`() {
        org.server.anonymous.ui.JavaFxTestSupport
            .init()
        val source = FakeNodeStatusSource()
        val vm = IdentityViewModel(source)
        source.emit(NodeStatus.Online("b".repeat(56) + ".onion"))
        // Platform.runLater is async; poll (off the FX thread) for the update.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline && vm.onionAddress.get() != "b".repeat(56) + ".onion") {
            Thread.sleep(20)
        }
        assertEquals("b".repeat(56) + ".onion", vm.onionAddress.get())
    }
}
