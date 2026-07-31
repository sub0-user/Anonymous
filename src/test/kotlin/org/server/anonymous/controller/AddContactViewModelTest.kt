package org.server.anonymous.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.server.anonymous.business.InMemoryContactService

class AddContactViewModelTest {
    private fun validAddress(): String = "z".repeat(56) + ".onion"

    @Test
    fun `adds a valid contact`() {
        val vm = AddContactViewModel(InMemoryContactService())
        vm.alias.set("friend")
        vm.address.set(validAddress())
        vm.add()
        assertNotNull(vm.addedContact.get())
        assertEquals("", vm.feedback.get())
    }

    @Test
    fun `rejects a blank alias`() {
        val vm = AddContactViewModel(InMemoryContactService())
        vm.alias.set("   ")
        vm.address.set(validAddress())
        vm.add()
        assertNull(vm.addedContact.get())
        assertEquals("Alias is required", vm.feedback.get())
    }

    @Test
    fun `rejects an invalid address`() {
        val vm = AddContactViewModel(InMemoryContactService())
        vm.alias.set("friend")
        vm.address.set("not-an-onion")
        vm.add()
        assertNull(vm.addedContact.get())
        assertTrue(vm.feedback.get().startsWith("Invalid v3 onion address"))
    }

    @Test
    fun `reports duplicates from the service`() {
        val service = InMemoryContactService()
        val first = AddContactViewModel(service)
        first.alias.set("a")
        first.address.set(validAddress())
        first.add()

        val second = AddContactViewModel(service)
        second.alias.set("b")
        second.address.set(validAddress())
        second.add()
        assertNull(second.addedContact.get())
        assertTrue(second.feedback.get().contains("already exists"))
    }
}
