package org.server.anonymous.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.server.anonymous.business.ContactBook

class RequestsViewModelTest {
    private val address = "b".repeat(56) + ".onion"

    @Test
    fun `starts with the pending requests`() {
        val book = ContactBook()
        book.addRequest(address, "hi there")
        val vm = RequestsViewModel(book)
        assertEquals(1, vm.requests.size)
        val request = vm.requests.first()
        assertEquals(address, request.address.value)
    }

    @Test
    fun `accept promotes the request to a contact`() {
        val book = ContactBook()
        book.addRequest(address, "hi there")
        val vm = RequestsViewModel(book)
        vm.accept(vm.requests.first())
        assertTrue(vm.requests.isEmpty())
        assertEquals(1, book.listContacts().size)
        val contact = book.listContacts().first()
        assertEquals(address, contact.address.value)
    }

    @Test
    fun `ignore drops the request`() {
        val book = ContactBook()
        book.addRequest(address, "hi there")
        val vm = RequestsViewModel(book)
        vm.ignore(vm.requests.first())
        assertTrue(vm.requests.isEmpty())
        assertTrue(book.listContacts().isEmpty())
    }

    @Test
    fun `block drops the request and blocks the address`() {
        val book = ContactBook()
        book.addRequest(address, "hi there")
        val vm = RequestsViewModel(book)
        vm.block(vm.requests.first())
        assertTrue(vm.requests.isEmpty())
        assertTrue(book.isBlocked(address))
    }
}
