package org.server.anonymous.controller

import javafx.beans.property.SimpleObjectProperty
import javafx.beans.property.SimpleStringProperty
import org.server.anonymous.business.OpResult
import org.server.anonymous.business.RoomMessenger
import org.server.anonymous.business.model.RoomRecord
import java.util.ResourceBundle

/** Join-room dialog: paste the invite and pick your display name, then join. */
class JoinRoomViewModel(
    private val roomMessenger: RoomMessenger,
    initialInvite: String? = null,
) {
    private val bundle = ResourceBundle.getBundle("org.server.anonymous.messages")

    val invite = SimpleStringProperty(initialInvite ?: "")
    val myName = SimpleStringProperty("")
    val feedback = SimpleStringProperty("")
    val result = SimpleObjectProperty<OpResult<RoomRecord>?>(null)

    fun acceptAndJoin() {
        feedback.set("")
        val accepted = roomMessenger.acceptInvite(invite.get(), myName.get())
        when (accepted) {
            is OpResult.Failure -> {
                feedback.set(accepted.reason)
                result.set(accepted)
            }
            is OpResult.Success -> {
                if (roomMessenger.join(accepted.value.id)) {
                    result.set(accepted)
                } else {
                    // The host was unreachable — keep the dialog open so the user can retry.
                    feedback.set(bundle.getString("room.join.unreachable"))
                    result.set(OpResult.Failure("Room host unreachable"))
                }
            }
        }
    }
}
