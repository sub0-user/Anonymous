package org.server.anonymous.controller

import javafx.application.Platform
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import org.server.anonymous.business.ContactService
import org.server.anonymous.business.IdentityKeys
import org.server.anonymous.business.IdentityService
import org.server.anonymous.business.MessageService
import org.server.anonymous.business.NodeStatus
import org.server.anonymous.business.NodeStatusSource
import org.server.anonymous.business.OpResult
import org.server.anonymous.business.SafetyNumber
import org.server.anonymous.business.model.Contact
import org.server.anonymous.business.model.MessageItem

class ChatViewModel(
    private val messageService: MessageService,
    private val contactService: ContactService,
    private val nodeStatusSource: NodeStatusSource,
    private val identityService: IdentityService,
    val contact: Contact,
) {
    val title = SimpleStringProperty(contact.alias)
    val subtitle = SimpleStringProperty("")
    val messages: ObservableList<MessageItem> = FXCollections.observableArrayList()
    val draft = SimpleStringProperty("")
    val sendFeedback = SimpleObjectProperty<String?>(null)
    val blocked = SimpleBooleanProperty(contactService.isBlocked(contact.address.value))

    private val myKeys by lazy { IdentityKeys.x25519KeyPairFromSeed(identityService.getOrCreate().seed) }
    private var lastSnapshot: List<MessageItem> = emptyList()

    init {
        syncMessages()
        messageService.addMessageListener { Platform.runLater { syncMessages() } }
        nodeStatusSource.addStatusListener { Platform.runLater { updateSubtitle() } }
        updateSubtitle()
    }

    fun send() {
        val body = draft.get().trim()
        if (body.isEmpty()) return
        when (val result = messageService.send(contact.id, body)) {
            is OpResult.Success -> {
                draft.set("")
                sendFeedback.set(null)
                syncMessages()
            }
            is OpResult.Failure -> sendFeedback.set(result.reason)
        }
    }

    fun toggleBlocked() {
        if (blocked.get()) {
            contactService.unblock(contact.address.value)
        } else {
            contactService.block(contact.address.value)
        }
        blocked.set(!blocked.get())
    }

    fun deleteContact(): Boolean = contactService.deleteContact(contact.id)

    private fun updateSubtitle() {
        val online = nodeStatusSource.status() as? NodeStatus.Online
        val peerKey = contactService.peerPublicKeyOf(contact.id)
        val safety =
            if (online != null && peerKey != null) {
                SafetyNumber.of(online.address, myKeys.publicKey, contact.address.value, peerKey)
            } else {
                null
            }
        subtitle.set(
            if (safety == null) {
                "E2E encrypted — safety number appears after the first message"
            } else {
                "E2E · verify out of band: $safety"
            },
        )
    }

    private fun syncMessages() {
        val fresh = messageService.messagesFor(contact.id)
        if (fresh != lastSnapshot) {
            lastSnapshot = fresh
            messages.setAll(fresh)
        }
    }
}
