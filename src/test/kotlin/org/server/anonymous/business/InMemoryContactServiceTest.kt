package org.server.anonymous.business

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.server.anonymous.business.model.Contact

class InMemoryContactServiceTest {
    // All-'z' address: valid v3 format, guaranteed distinct from the seeded mock addresses.
    private fun validAddress(): String = "z".repeat(56) + ".onion"

    @Test
    fun `lists three seeded contacts`() {
        val service = InMemoryContactService()
        val contacts = service.listContacts()
        assertEquals(3, contacts.size)
        assertEquals(listOf("raven", "ghost", "moth"), contacts.map { it.alias })
    }

    @Test
    fun `adds a contact with a valid address`() {
        val service = InMemoryContactService()
        val result = service.addContact("newbie", validAddress())
        assertTrue(result is OpResult.Success<Contact>)
        assertEquals("newbie", (result as OpResult.Success<Contact>).value.alias)
        assertEquals(4, service.listContacts().size)
    }

    @Test
    fun `rejects a duplicate address`() {
        val service = InMemoryContactService()
        val first = service.addContact("a", validAddress())
        assertTrue(first is OpResult.Success<Contact>)
        val address = (first as OpResult.Success<Contact>).value.address.value
        assertTrue(service.addContact("b", address) is OpResult.Failure)
    }

    @Test
    fun `rejects an invalid address`() {
        val service = InMemoryContactService()
        assertTrue(service.addContact("x", "not-an-onion") is OpResult.Failure)
    }

    @Test
    fun `rejects a blank alias`() {
        val service = InMemoryContactService()
        assertTrue(service.addContact("   ", validAddress()) is OpResult.Failure)
    }
}
