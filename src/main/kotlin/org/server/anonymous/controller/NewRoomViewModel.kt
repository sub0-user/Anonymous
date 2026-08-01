package org.server.anonymous.controller

import javafx.beans.property.SimpleObjectProperty
import javafx.beans.property.SimpleStringProperty
import org.server.anonymous.business.OpResult
import org.server.anonymous.business.RoomHost
import org.server.anonymous.business.model.RoomRecord
import org.server.anonymous.business.model.RoomType

/** New-room dialog: name, display name, and private/public type. */
class NewRoomViewModel(
    private val roomHost: RoomHost,
) {
    val name = SimpleStringProperty("")
    val myName = SimpleStringProperty("")
    val type = SimpleObjectProperty(RoomType.PRIVATE)
    val feedback = SimpleStringProperty("")
    val result = SimpleObjectProperty<OpResult<RoomRecord>?>(null)

    fun create() {
        feedback.set("")
        val outcome = roomHost.createRoom(name.get(), type.get(), myName.get())
        if (outcome is OpResult.Failure) feedback.set(outcome.reason)
        result.set(outcome)
    }
}
