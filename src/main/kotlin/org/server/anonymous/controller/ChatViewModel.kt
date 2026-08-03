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
import org.server.anonymous.business.model.MessageDirection
import org.server.anonymous.business.model.MessageItem
import org.server.anonymous.business.model.ReplyRef
import java.util.ResourceBundle

@Suppress("TooManyFunctions") // one cohesive surface over a small fixed action set
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

    /** The message being replied to, if any — shown as a bar above the composer. */
    val replyingTo = SimpleObjectProperty<MessageItem?>(null)
    val replyBarLabel = SimpleStringProperty("")

    private val bundle = ResourceBundle.getBundle("org.server.anonymous.messages")
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
        val reply = replyingTo.get()
        when (val result = messageService.send(contact.id, body, reply?.let { replyRefFor(it) })) {
            is OpResult.Success -> {
                draft.set("")
                clearReply()
                sendFeedback.set(null)
                syncMessages()
            }
            is OpResult.Failure -> sendFeedback.set(result.reason)
        }
    }

    /** Starts a reply to [item]; the composer bar shows until the reply is sent or dismissed. */
    fun replyTo(item: MessageItem) {
        replyingTo.set(item)
        val template = bundle.getString("chat.reply.bar")
        replyBarLabel.set(
            template.replace("{name}", nameFor(item)).replace("{preview}", ReplyRef.previewOf(item.body)),
        )
    }

    fun clearReply() {
        replyingTo.set(null)
        replyBarLabel.set("")
    }

    /** Resolves a carried reply reference to a display name ("You" / the contact / the carried name). */
    fun replyName(ref: ReplyRef): String =
        when {
            ref.senderKey != null && ref.senderKey.contentEquals(myKeys.publicKey) -> bundle.getString("chat.you")
            ref.senderKey != null && ref.senderKey.contentEquals(contact.peerPublicKey) -> contact.alias
            !ref.senderName.isNullOrBlank() -> ref.senderName
            else -> bundle.getString("chat.you")
        }

    /** Builds the wire reference for a reply to [item] from this conversation. */
    private fun replyRefFor(item: MessageItem): ReplyRef =
        ReplyRef(
            senderKey =
                if (item.direction == MessageDirection.OUT) {
                    myKeys.publicKey
                } else {
                    contact.peerPublicKey
                },
            senderName = nameFor(item),
            text = ReplyRef.previewOf(item.body),
        )

    private fun nameFor(item: MessageItem): String =
        if (item.direction == MessageDirection.OUT) bundle.getString("chat.you") else contact.alias

    fun toggleBlocked() {
        if (blocked.get()) {
            contactService.unblock(contact.address.value)
        } else {
            contactService.block(contact.address.value)
        }
        blocked.set(!blocked.get())
    }

    fun deleteContact(): Boolean = contactService.deleteContact(contact.id)

    /** Clears this conversation's history from memory and from at-rest storage. */
    fun clearHistory() {
        messageService.clearHistory(contact.id)
        syncMessages()
    }

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
