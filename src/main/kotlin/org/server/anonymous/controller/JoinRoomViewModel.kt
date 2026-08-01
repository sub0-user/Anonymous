package org.server.anonymous.controller

import javafx.beans.property.SimpleObjectProperty
import javafx.beans.property.SimpleStringProperty
import org.server.anonymous.business.OpResult
import org.server.anonymous.business.RoomMessenger
import org.server.anonymous.business.model.RoomRecord

/** Join-room dialog: paste the invite and pick your display name, then join. */
class JoinRoomViewModel(
    private val roomMessenger: RoomMessenger,
) {
    val invite = SimpleStringProperty("")
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
                roomMessenger.join(accepted.value.id)
                result.set(accepted)
            }
        }
    }
}
