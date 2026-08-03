package org.server.anonymous.controller

import javafx.application.Platform
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.property.SimpleStringProperty
import org.server.anonymous.business.OpResult
import org.server.anonymous.business.RoomMessenger
import org.server.anonymous.business.model.RoomRecord
import java.util.ResourceBundle
import java.util.concurrent.Executors

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

    /** True while the JOIN is in flight; the dialog's Join button disables then. */
    val busy = SimpleBooleanProperty(false)

    private val executor =
        Executors.newSingleThreadExecutor { r -> Thread(r, "room-join").apply { isDaemon = true } }

    fun acceptAndJoin() {
        if (busy.get()) return
        feedback.set("")
        busy.set(true)
        executor.execute {
            val accepted = roomMessenger.acceptInvite(invite.get(), myName.get())
            val unreachable = accepted is OpResult.Success && !roomMessenger.join(accepted.value.id)
            val outcome = if (unreachable) OpResult.Failure("Room host unreachable") else accepted
            Platform.runLater {
                busy.set(false)
                if (unreachable) {
                    // The host was unreachable — keep the dialog open so the user can retry.
                    feedback.set(bundle.getString("room.join.unreachable"))
                }
                result.set(outcome)
            }
        }
    }
}
