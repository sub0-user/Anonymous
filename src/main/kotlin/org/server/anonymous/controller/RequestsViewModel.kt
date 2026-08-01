package org.server.anonymous.controller

import javafx.beans.property.SimpleObjectProperty
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import org.server.anonymous.business.ContactService
import org.server.anonymous.business.OpResult
import org.server.anonymous.business.model.ContactRequest

/** The approval gate for messages from addresses that are not contacts yet. */
class RequestsViewModel(
    private val contactService: ContactService,
) {
    val requests: ObservableList<ContactRequest> = FXCollections.observableArrayList()
    val message = SimpleObjectProperty<String?>(null)

    init {
        refresh()
    }

    fun refresh() {
        requests.setAll(contactService.incomingRequests())
    }

    fun accept(request: ContactRequest) {
        when (val result = contactService.acceptRequest(request.address.value)) {
            is OpResult.Success -> refresh()
            is OpResult.Failure -> message.set(result.reason)
        }
    }

    fun ignore(request: ContactRequest) {
        contactService.ignoreRequest(request.address.value)
        refresh()
    }

    fun block(request: ContactRequest) {
        contactService.block(request.address.value)
        refresh()
    }
}
