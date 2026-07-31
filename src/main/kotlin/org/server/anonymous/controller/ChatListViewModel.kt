package org.server.anonymous.controller

import javafx.beans.property.SimpleObjectProperty
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import javafx.collections.transformation.FilteredList
import org.server.anonymous.business.ContactService
import org.server.anonymous.business.model.Contact
import java.util.function.Predicate

class ChatListViewModel(
    private val contactService: ContactService,
) {
    val contacts: ObservableList<Contact> = FXCollections.observableArrayList()
    val filteredContacts: FilteredList<Contact> = FilteredList(contacts) { true }
    val searchQuery = SimpleStringProperty("")
    val selectedContact = SimpleObjectProperty<Contact?>(null)

    init {
        refresh()
        searchQuery.addListener { _, _, query ->
            filteredContacts.predicate =
                Predicate { contact ->
                    query.isBlank() ||
                        contact.alias.contains(query, ignoreCase = true) ||
                        contact.address.value.contains(query, ignoreCase = true)
                }
        }
    }

    fun refresh() {
        contacts.setAll(contactService.listContacts())
    }
}
