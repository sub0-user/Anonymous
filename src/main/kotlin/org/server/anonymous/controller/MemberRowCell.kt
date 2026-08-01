package org.server.anonymous.controller

import javafx.fxml.FXML
import javafx.fxml.FXMLLoader
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.layout.HBox
import org.server.anonymous.business.model.RoomMember

/** One member row in the members dialog: name, status, and (founder) rename/remove. */
class MemberRowCell(
    private val founder: Boolean,
    private val onRename: (RoomMember) -> Unit,
    private val onRemove: (RoomMember) -> Unit,
) : ListCell<RoomMember>() {
    @FXML private lateinit var nameLabel: Label

    @FXML private lateinit var statusLabel: Label

    @FXML private lateinit var renameButton: Button

    @FXML private lateinit var removeButton: Button

    private val rootNode: HBox

    init {
        val loader = FXMLLoader(MemberRowCell::class.java.getResource("members-row.fxml"))
        loader.setController(this)
        rootNode = loader.load<HBox>()
    }

    override fun updateItem(
        item: RoomMember?,
        empty: Boolean,
    ) {
        super.updateItem(item, empty)
        if (item == null || empty) {
            graphic = null
            return
        }
        nameLabel.text = item.name
        statusLabel.text = item.status.name.lowercase()
        renameButton.isVisible = founder
        removeButton.isVisible = founder
        renameButton.isManaged = founder
        removeButton.isManaged = founder
        renameButton.setOnAction { onRename(item) }
        removeButton.setOnAction { onRemove(item) }
        graphic = rootNode
    }
}
