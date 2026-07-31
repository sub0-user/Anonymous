package org.server.anonymous.controller

import javafx.beans.property.SimpleObjectProperty
import javafx.beans.property.SimpleStringProperty
import org.server.anonymous.business.ContactService
import org.server.anonymous.business.OnionAddressValidator
import org.server.anonymous.business.OpResult
import org.server.anonymous.business.model.Contact

class AddContactViewModel(
    private val contactService: ContactService,
) {
    val alias = SimpleStringProperty("")
    val address = SimpleStringProperty("")
    val feedback = SimpleStringProperty("")
    val addedContact = SimpleObjectProperty<Contact?>(null)

    fun add() {
        val aliasText = alias.get().trim()
        val addressText = address.get().trim()
        if (aliasText.isEmpty()) {
            feedback.set("Alias is required")
            return
        }
        if (!OnionAddressValidator.isValid(addressText)) {
            feedback.set("Invalid v3 onion address — 56 chars (a–z, 2–7) ending in .onion")
            return
        }
        when (val result = contactService.addContact(aliasText, addressText)) {
            is OpResult.Success -> {
                addedContact.set(result.value)
                feedback.set("")
            }
            is OpResult.Failure -> feedback.set(result.reason)
        }
    }
}
