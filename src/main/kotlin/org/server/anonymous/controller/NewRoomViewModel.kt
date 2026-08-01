package org.server.anonymous.controller

import javafx.application.Platform
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.property.SimpleStringProperty
import org.server.anonymous.business.OpResult
import org.server.anonymous.business.RoomHost
import org.server.anonymous.business.model.RoomRecord
import org.server.anonymous.business.model.RoomType
import java.util.concurrent.Executors

/** New-room dialog: name, display name, and private/public type. */
class NewRoomViewModel(
    private val roomHost: RoomHost,
) {
    val name = SimpleStringProperty("")
    val myName = SimpleStringProperty("")
    val type = SimpleObjectProperty(RoomType.PRIVATE)
    val feedback = SimpleStringProperty("")
    val result = SimpleObjectProperty<OpResult<RoomRecord>?>(null)

    /** True while the room service is being published — the dialog shows progress (Phase B3). */
    val busy = SimpleBooleanProperty(false)

    private val executor =
        Executors.newSingleThreadExecutor { r -> Thread(r, "room-create").apply { isDaemon = true } }

    /** Synchronous create; kept for tests. */
    fun create() {
        feedback.set("")
        val outcome = roomHost.createRoom(name.get(), type.get(), myName.get())
        if (outcome is OpResult.Failure) feedback.set(outcome.reason)
        result.set(outcome)
    }

    /**
     * Creates the room off the FX thread — publishing an onion service can take a minute on a
     * slow network and must never freeze the dialog. [onDone] runs on the FX thread.
     */
    fun createAsync(onDone: () -> Unit) {
        if (busy.get()) return
        busy.set(true)
        feedback.set("")
        executor.execute {
            val outcome = roomHost.createRoom(name.get(), type.get(), myName.get())
            Platform.runLater {
                busy.set(false)
                if (outcome is OpResult.Failure) feedback.set(outcome.reason)
                result.set(outcome)
                onDone()
            }
        }
    }
}
