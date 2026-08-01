package org.server.anonymous.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.server.anonymous.business.NodeStatus

class SettingsViewModelTest {
    @Test
    fun `shows node status from the source`() {
        val source = FakeNodeStatusSource()
        val vm = SettingsViewModel(source)
        vm.applyStatus(NodeStatus.Online("a".repeat(56) + ".onion", 9050))
        assertEquals("Node online — receiving messages", vm.nodeStatus.get())
        vm.applyStatus(NodeStatus.Offline("stopped"))
        assertEquals("node offline: stopped", vm.nodeStatus.get())
    }
}
