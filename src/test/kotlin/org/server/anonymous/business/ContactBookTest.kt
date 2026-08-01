package org.server.anonymous.business

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.server.anonymous.business.model.ContactRequest

class ContactBookTest {
    private val addressA = "a".repeat(56) + ".onion"
    private val addressB = "b".repeat(56) + ".onion"

    @Test
    fun `starts empty`() {
        val book = ContactBook()
        assertTrue(book.listContacts().isEmpty())
        assertTrue(book.incomingRequests().isEmpty())
    }

    @Test
    fun `adds and finds contacts`() {
        val book = ContactBook()
        val result = book.addContact("alice", addressA)
        assertTrue(result is OpResult.Success)
        assertEquals(addressA, book.findByAddress(addressA)?.address?.value)
        assertNull(book.findByAddress(addressB))
    }

    @Test
    fun `rejects duplicate and invalid addresses`() {
        val book = ContactBook()
        book.addContact("alice", addressA)
        assertTrue(book.addContact("again", addressA) is OpResult.Failure)
        assertTrue(book.addContact("bad", "not-an-onion") is OpResult.Failure)
    }

    @Test
    fun `delete removes a contact`() {
        val book = ContactBook()
        val contact = (book.addContact("alice", addressA) as OpResult.Success).value
        assertTrue(book.deleteContact(contact.id))
        assertNull(book.findByAddress(addressA))
        assertFalse(book.deleteContact(contact.id))
    }

    @Test
    fun `block hides contacts and clears their requests`() {
        val book = ContactBook()
        book.addRequest(addressA, "hello?")
        book.block(addressA)
        assertTrue(book.isBlocked(addressA))
        assertTrue(book.incomingRequests().isEmpty())
        book.unblock(addressA)
        assertFalse(book.isBlocked(addressA))
    }

    @Test
    fun `request lifecycle add ignore then accept`() {
        val book = ContactBook()
        book.addRequest(addressA, "hi from a stranger")
        val request: ContactRequest = book.incomingRequests().single()
        assertEquals(addressA, request.address.value)
        assertEquals("hi from a stranger", request.preview)

        book.ignoreRequest(addressA)
        assertTrue(book.incomingRequests().isEmpty())

        book.addRequest(addressB, "hello again")
        val accepted = book.acceptRequest(addressB)
        assertTrue(accepted is OpResult.Success)
        assertNotNull(book.findByAddress(addressB))
        assertTrue(book.incomingRequests().isEmpty())
    }

    @Test
    fun `duplicate requests are ignored`() {
        val book = ContactBook()
        book.addRequest(addressA, "one")
        book.addRequest(addressA, "two")
        assertEquals(1, book.incomingRequests().size)
    }

    @Test
    fun `accepting a non-existent request fails`() {
        assertTrue(ContactBook().acceptRequest(addressA) is OpResult.Failure)
    }

    @Test
    fun `peer keys bind and read back per contact`() {
        val book = ContactBook()
        val contact = (book.addContact("alice", addressA) as OpResult.Success).value
        assertNull(book.peerPublicKeyOf(contact.id))
        val key = ByteArray(32) { 7 }
        book.bindPeerKey(contact.id, key)
        assertEquals(key.toList(), book.peerPublicKeyOf(contact.id)?.toList())
    }
}
