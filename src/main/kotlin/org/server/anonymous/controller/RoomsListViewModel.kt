package org.server.anonymous.controller

import javafx.collections.FXCollections
import javafx.collections.ObservableList
import org.server.anonymous.business.RoomMessenger
import org.server.anonymous.business.model.RoomRecord

/** The sidebar's rooms section: every room the node hosts or belongs to. */
class RoomsListViewModel(
    private val roomMessenger: RoomMessenger,
) {
    val rooms: ObservableList<RoomRecord> = FXCollections.observableArrayList()

    init {
        refresh()
    }

    fun refresh() {
        rooms.setAll(roomMessenger.rooms())
    }
}
