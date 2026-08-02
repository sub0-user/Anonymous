package org.server.anonymous.controller

import javafx.application.Platform
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import org.server.anonymous.business.MessageService
import org.server.anonymous.business.OpResult
import org.server.anonymous.business.RoomHost
import org.server.anonymous.business.RoomMessenger
import org.server.anonymous.business.model.Contact
import org.server.anonymous.business.model.MemberStatus
import org.server.anonymous.business.model.RoomMember
import org.server.anonymous.business.model.RoomMessageItem
import org.server.anonymous.business.model.RoomRecord

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

    val isFounder: Boolean
        get() = currentRoom()?.isFounder == true

    init {
        roomMessenger.addMessageListener { message ->
            if (message.roomId == roomId) Platform.runLater { sync() }
        }
        sync()
    }

    fun contactsForInvite(): List<Contact> = contacts().filter { it.peerPublicKey != null }

    fun members(): List<RoomMember> = currentRoom()?.members ?: emptyList()

    fun displayNameFor(key: ByteArray): String = memberName(key) ?: "?"

    private fun memberName(key: ByteArray): String? = members().firstOrNull { it.publicKey.contentEquals(key) }?.name

    fun send() {
        sendFeedback.set("")
        val body = draft.get().trim()
        if (body.isEmpty()) return
        when (val result = roomMessenger.sendMessage(roomId, body)) {
            is OpResult.Failure -> sendFeedback.set(result.reason)
            is OpResult.Success -> {
                draft.set("")
                val recipients = (members().count { it.status == MemberStatus.MEMBER } - 1).coerceAtLeast(0)
                if (result.value < recipients) {
                    sendFeedback.set("sent to ${result.value} of $recipients members")
                }
            }
        }
        sync()
    }

    fun copyInvite(
        contact: Contact,
        memberName: String,
        expiryDays: Long?,
    ): OpResult<String> =
        roomHost?.createInvite(
            roomId,
            contact.address.value,
            contact.peerPublicKey!!,
            memberName,
            expiryDays?.let { days ->
                if (days > 0) System.currentTimeMillis() / 1000 + days * 86_400 else null
            },
        ) ?: OpResult.Failure("Only the founder can invite")

    /**
     * Delivers the invite as a 1:1 chat message so the invitee can tap "Join room" —
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
