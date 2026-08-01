package org.server.anonymous.controller

import javafx.scene.control.ListCell
import org.server.anonymous.business.model.RoomRecord
import org.server.anonymous.business.model.RoomType

/** One room in the sidebar: name plus a private/public marker. */
class RoomCell : ListCell<RoomRecord>() {
    override fun updateItem(
        item: RoomRecord?,
        empty: Boolean,
    ) {
        super.updateItem(item, empty)
        text =
            if (empty || item == null) {
                null
            } else {
                val marker = if (item.type == RoomType.PRIVATE) "🔒" else "🌐"
                "$marker ${item.name}"
            }
    }
}
