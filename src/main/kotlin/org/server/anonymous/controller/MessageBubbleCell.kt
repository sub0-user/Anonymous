package org.server.anonymous.controller

import javafx.fxml.FXML
import javafx.fxml.FXMLLoader
import javafx.geometry.Pos
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.layout.HBox
import org.server.anonymous.business.model.MessageDirection
import org.server.anonymous.business.model.MessageItem
import org.server.anonymous.business.model.MessageStatus

/** Renders one message bubble from message-bubble-cell.fxml. */
class MessageBubbleCell : ListCell<MessageItem>() {
    @FXML private lateinit var bubble: HBox

    @FXML private lateinit var bodyLabel: Label

    @FXML private lateinit var metaLabel: Label

    private val rootNode: HBox

    init {
        val loader = FXMLLoader(MessageBubbleCell::class.java.getResource("message-bubble-cell.fxml"))
        loader.setController(this)
        rootNode = loader.load<HBox>()
    }

    override fun updateItem(
        item: MessageItem?,
        empty: Boolean,
    ) {
        super.updateItem(item, empty)
        if (item == null || empty) {
            graphic = null
            return
        }
        bodyLabel.text = item.body
        metaLabel.text =
            when (item.direction) {
                MessageDirection.IN -> item.sentAtLabel
                MessageDirection.OUT ->
                    item.sentAtLabel + "  " + if (item.status == MessageStatus.DELIVERED) "✓✓" else "✓"
            }
        bubble.styleClass.removeAll("bubble-out", "bubble-in")
        bubble.styleClass.add(if (item.direction == MessageDirection.OUT) "bubble-out" else "bubble-in")
        alignment = if (item.direction == MessageDirection.OUT) Pos.CENTER_RIGHT else Pos.CENTER_LEFT
        graphic = rootNode
    }
}
