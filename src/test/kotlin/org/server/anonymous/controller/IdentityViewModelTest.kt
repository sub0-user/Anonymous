package org.server.anonymous.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IdentityViewModelTest {
    @Test
    fun `node status is honest about demo mode`() {
        assertEquals("Node: demo mode — real Tor arrives in Phase 2", IdentityViewModel().nodeStatus.get())
    }

    @Test
    fun `onion address is present`() {
        assertTrue(IdentityViewModel().onionAddress.get().endsWith(".onion"))
    }
}
