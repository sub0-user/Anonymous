package org.server.anonymous.controller

import javafx.application.Platform
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import org.server.anonymous.business.MessageService
import org.server.anonymous.business.OpResult
import org.server.anonymous.business.RoomHost
import org.server.anonymous.business.RoomMessenger
import org.server.anonymous.business.model.Contact
import org.server.anonymous.business.model.MemberStatus
import org.server.anonymous.business.model.ReplyRef
import org.server.anonymous.business.model.RoomMember
import org.server.anonymous.business.model.RoomMessageItem
import org.server.anonymous.business.model.RoomRecord
import java.util.ResourceBundle

/** One open room chat: message list, room header, and (for the founder) member actions. */
@Suppress("TooManyFunctions") // one cohesive surface over a small fixed action set
class RoomChatViewModel(
    private val roomMessenger: RoomMessenger,
    private val roomHost: RoomHost?,
    private val roomId: Long,
    private val contacts: () -> List<Contact>,
    private val messageService: MessageService? = null,
) {
    val messages: ObservableList<RoomMessageItem> = FXCollections.observableArrayList()
    val title = SimpleStringProperty("")
    val subtitle = SimpleStringProperty("")
    val draft = SimpleStringProperty("")
    val sendFeedback = SimpleStringProperty("")
    val inviteFeedback = SimpleStringProperty("")
    val founderVisible = SimpleBooleanProperty(false)

    /** The room message being replied to, if any — shown as a bar above the composer. */
    val replyingTo = SimpleObjectProperty<RoomMessageItem?>(null)
    val replyBarLabel = SimpleStringProperty("")

    private val bundle = ResourceBundle.getBundle("org.server.anonymous.messages")

    val isFounder: Boolean
        get() = currentRoom()?.isFounder == true

    init {
        roomMessenger.addMessageListener { message ->
            if (message.roomId == roomId) Platform.runLater { sync() }
        }
        sync()
    }

    /** Every contact can be added — being a contact already implies trust. */
    fun contactsForInvite(): List<Contact> {
        val memberKeys = members().map { it.publicKey }
        return contacts().filter { contact ->
            val key = contact.peerPublicKey
            key == null || memberKeys.none { it.contentEquals(key) }
        }
    }

    fun members(): List<RoomMember> = currentRoom()?.members ?: emptyList()

    fun displayNameFor(key: ByteArray): String = memberName(key) ?: "?"

    private fun memberName(key: ByteArray): String? = members().firstOrNull { it.publicKey.contentEquals(key) }?.name

    fun send() {
        sendFeedback.set("")
        val body = draft.get().trim()
        if (body.isEmpty()) return
        val reply = replyingTo.get()
        when (val result = roomMessenger.sendMessage(roomId, body, reply?.let { replyRefFor(it) })) {
            is OpResult.Failure -> sendFeedback.set(result.reason)
            is OpResult.Success -> {
                draft.set("")
                clearReply()
                val recipients = (members().count { it.status == MemberStatus.MEMBER } - 1).coerceAtLeast(0)
                if (result.value < recipients) {
                    sendFeedback.set("sent to ${result.value} of $recipients members")
                }
            }
        }
        sync()
    }

    /** Starts a reply to [item]; the composer bar shows until the reply is sent or dismissed. */
    fun replyTo(item: RoomMessageItem) {
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

    /** Resolves a carried reply reference to a display name (member by key, else the carried name). */
    fun replyName(ref: ReplyRef): String =
        ref.senderKey?.let { key -> memberName(key) }
            ?: ref.senderName?.takeIf { it.isNotBlank() }
            ?: "?"

    /** Builds the wire reference for a reply to [item] in this room. */
    private fun replyRefFor(item: RoomMessageItem): ReplyRef =
        ReplyRef(
            senderKey = item.senderPublicKey,
            senderName = nameFor(item),
            text = ReplyRef.previewOf(item.body),
        )

    private fun nameFor(item: RoomMessageItem): String =
        if (item.isOutgoing) bundle.getString("chat.you") else displayNameFor(item.senderPublicKey)

    /**
     * Creates the invite for a contact. A contact we have never talked to has no cached key,
     * so exchange one first via a session handshake (off the FX thread, from the add dialog);
     * contacts we have talked to are added instantly with their cached key. Creating the
     * invite is purely local — the room service is never re-published, so this cannot fail
     * on the network.
     */
    fun addMember(
        contact: Contact,
        memberName: String,
        expiryDays: Long?,
    ): OpResult<String> {
        val cachedKey = contact.peerPublicKey
        val keyResult =
            if (cachedKey != null) {
                OpResult.Success(cachedKey)
            } else {
                messageService?.probePeerKey(contact) ?: OpResult.Failure("Chat messages are not available")
            }
        val peerKey =
            when (keyResult) {
                is OpResult.Failure -> return keyResult
                is OpResult.Success -> keyResult.value
            }
        return roomHost?.createInvite(
            roomId,
            contact.address.value,
            peerKey,
            memberName,
            expiryDays?.let { days ->
                if (days > 0) System.currentTimeMillis() / 1000 + days * 86_400 else null
            },
        ) ?: OpResult.Failure("Only the founder can invite")
    }

    /**
     * Delivers the invite as a 1:1 chat message so the invitee can tap "Accept" —
     * rides the offline outbox, so the invite arrives even if they are not online right now.
     */
    fun sendInvite(
        contact: Contact,
        invite: String,
    ): OpResult<Unit> {
        val service = messageService ?: return OpResult.Failure("Chat messages are not available")
        return when (val result = service.send(contact.id, invite)) {
            is OpResult.Success -> OpResult.Success(Unit)
            is OpResult.Failure -> OpResult.Failure(result.reason)
        }
    }

    fun removeMember(member: RoomMember): Boolean {
        val ok = roomHost?.kickMember(roomId, member.publicKey) == true
        sync()
        return ok
    }

    /** Member exits the room: sends LEAVE and drops the local record. */
    fun leaveRoom(): Boolean = roomMessenger.leaveRoom(roomId)

    /** Founder tears the room down: onion service removed and the record deleted. */
    fun deleteRoom(): Boolean = roomHost?.deleteRoom(roomId) == true

    fun renameMember(
        member: RoomMember,
        newName: String,
    ): Boolean {
        val ok = roomHost?.renameMember(roomId, member.publicKey, newName) == true
        sync()
        return ok
    }

    private fun currentRoom(): RoomRecord? = roomMessenger.rooms().firstOrNull { it.id == roomId }

    /** Clears this room's history from memory and from at-rest storage. */
    fun clearHistory() {
        roomMessenger.clearHistory(roomId)
        sync()
    }

    /** Re-reads the room record (after dialogs or membership changes). */
    fun syncAfterDialog() {
        sync()
    }

    private fun sync() {
        val room = currentRoom() ?: return
        title.set(room.name)
        val memberCount = members().count { it.status == MemberStatus.MEMBER }
        subtitle.set("${room.type.name.lowercase()} · $memberCount member(s)")
        founderVisible.set(room.isFounder)
        messages.setAll(roomMessenger.messagesFor(roomId))
    }
}

/** What happened to a freshly created invite: whether it was also delivered as a chat message. */
data class InviteOutcome(
    val invite: String,
    val contactAlias: String,
    val delivered: Boolean,
    val sendError: String?,
)
