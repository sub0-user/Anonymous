package org.server.anonymous.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.server.anonymous.business.InMemoryContactService

class ChatListViewModelTest {
    @Test
    fun `loads contacts from the service`() {
        val vm = ChatListViewModel(InMemoryContactService())
        assertEquals(3, vm.contacts.size)
        assertEquals("raven", vm.contacts.first().alias)
    }

    @Test
    fun `blank search shows all contacts`() {
        val vm = ChatListViewModel(InMemoryContactService())
        vm.searchQuery.set("")
        assertEquals(3, vm.filteredContacts.size)
    }

    @Test
    fun `search filters by alias`() {
        val vm = ChatListViewModel(InMemoryContactService())
        vm.searchQuery.set("moth")
        assertEquals(listOf("moth"), vm.filteredContacts.map { it.alias })
    }

    @Test
    fun `search filters by address`() {
        val vm = ChatListViewModel(InMemoryContactService())
        val address =
            vm.contacts[0]
                .address.value
        vm.searchQuery.set(address)
        assertEquals(listOf("raven"), vm.filteredContacts.map { it.alias })
    }
}
