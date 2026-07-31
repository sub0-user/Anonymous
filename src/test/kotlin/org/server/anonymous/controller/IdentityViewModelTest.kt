package org.server.anonymous.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IdentityViewModelTest {
    @Test
    fun `node status reports online`() {
        assertEquals("Node online — receiving messages", IdentityViewModel().nodeStatus.get())
    }

    @Test
    fun `onion address is present`() {
        assertTrue(IdentityViewModel().onionAddress.get().endsWith(".onion"))
    }
}
