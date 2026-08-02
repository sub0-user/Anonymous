package org.server.anonymous.controller

import javafx.fxml.FXML
import javafx.fxml.FXMLLoader
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.layout.HBox
import org.server.anonymous.business.model.MessageDirection
import org.server.anonymous.business.model.MessageItem
import org.server.anonymous.business.model.MessageStatus
import java.util.ResourceBundle

/**
 * Renders one message bubble from message-bubble-cell.fxml.
 * Received room invites (the app's own invite format) get a one-tap "Join room" button.
 */
class MessageBubbleCell(
    private val onJoinInvite: (String) -> Unit = {},
) : ListCell<MessageItem>() {
    @FXML private lateinit var bubble: HBox

    @FXML private lateinit var bodyLabel: Label

    @FXML private lateinit var metaLabel: Label

    @FXML internal lateinit var joinButton: Button // internal: fired directly by MessageBubbleCellTest

    private val rootNode: HBox

    private var currentInvite: String? = null

    init {
        val bundle = ResourceBundle.getBundle("org.server.anonymous.messages")
        val loader = FXMLLoader(MessageBubbleCell::class.java.getResource("message-bubble-cell.fxml"), bundle)
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
                    item.sentAtLabel + "  " +
                        when (item.status) {
                            MessageStatus.DELIVERED -> "✓✓"
                            MessageStatus.SENT -> "✓"
                            MessageStatus.FAILED -> "✗"
                        }
            }
        bubble.styleClass.removeAll("bubble-out", "bubble-in")
        bubble.styleClass.add(if (item.direction == MessageDirection.OUT) "bubble-out" else "bubble-in")
        alignment = if (item.direction == MessageDirection.OUT) Pos.CENTER_RIGHT else Pos.CENTER_LEFT
        currentInvite =
            if (item.direction == MessageDirection.IN && item.body.isRoomInvite()) {
                item.body
            } else {
                null
            }
        val showJoin = currentInvite != null
        joinButton.isVisible = showJoin
        joinButton.isManaged = showJoin
        graphic = rootNode
    }

    /** Test seam: renders [item] the way a real list would (ListCell.updateItem is protected). */
    internal fun render(item: MessageItem?) = updateItem(item, false)

    @Suppress("UnusedPrivateMember") // invoked reflectively by FXML
    @FXML
    private fun onJoinClicked() {
        currentInvite?.let(onJoinInvite)
    }

    private fun String.isRoomInvite(): Boolean = startsWith("inv4p:") || startsWith("inv4u:")
}
