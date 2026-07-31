package org.server.anonymous.controller

import javafx.beans.property.SimpleObjectProperty
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import org.server.anonymous.business.MessageService
import org.server.anonymous.business.OpResult
import org.server.anonymous.business.model.Contact
import org.server.anonymous.business.model.MessageItem

class ChatViewModel(
    private val messageService: MessageService,
    val contact: Contact,
) {
    val title = SimpleStringProperty(contact.alias)
    val subtitle = SimpleStringProperty("● online · E2E encrypted")
    val messages: ObservableList<MessageItem> = FXCollections.observableArrayList()
    val draft = SimpleStringProperty("")
    val sendFeedback = SimpleObjectProperty<String?>(null)

    init {
        messages.setAll(messageService.messagesFor(contact.id))
    }

    fun send() {
        val body = draft.get().trim()
        if (body.isEmpty()) return
        when (val result = messageService.send(contact.id, body)) {
            is OpResult.Success -> {
                messages += result.value
                draft.set("")
                sendFeedback.set(null)
            }
            is OpResult.Failure -> sendFeedback.set(result.reason)
        }
    }
}
