package org.server.anonymous.controller

import javafx.fxml.FXML
import javafx.fxml.FXMLLoader
import javafx.geometry.Pos
import javafx.scene.control.ContextMenu
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.MenuItem
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.layout.HBox
import org.server.anonymous.business.model.RoomMessageItem
import java.util.ResourceBundle

/** Renders one room message: "name · body" bubble with a time label, reusing the 1:1 bubble. */
class RoomMessageCell(
    private val displayNameFor: (ByteArray) -> String,
) : ListCell<RoomMessageItem>() {
    @FXML private lateinit var bubble: HBox

    @FXML private lateinit var bodyLabel: Label

    @FXML private lateinit var metaLabel: Label

    private val rootNode: HBox

    private var currentBody: String? = null

    init {
        val bundle = ResourceBundle.getBundle("org.server.anonymous.messages")
        val loader = FXMLLoader(RoomMessageCell::class.java.getResource("message-bubble-cell.fxml"), bundle)
        loader.setController(this)
        rootNode = loader.load<HBox>()
        val copyItem = MenuItem(bundle.getString("chat.copy"))
        copyItem.setOnAction {
            currentBody?.let { body ->
                Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putString(body) })
            }
        }
        contextMenu = ContextMenu(copyItem)
    }

    override fun updateItem(
        item: RoomMessageItem?,
        empty: Boolean,
    ) {
        super.updateItem(item, empty)
        if (item == null || empty) {
            graphic = null
            currentBody = null
            return
        }
        currentBody = item.body
        bodyLabel.text = "${displayNameFor(item.senderPublicKey)}: ${item.body}"
        metaLabel.text = item.timeLabel
        bubble.styleClass.removeAll("bubble-out", "bubble-in")
        bubble.styleClass.add(if (item.isOutgoing) "bubble-out" else "bubble-in")
        alignment = if (item.isOutgoing) Pos.CENTER_RIGHT else Pos.CENTER_LEFT
        graphic = rootNode
    }
}
