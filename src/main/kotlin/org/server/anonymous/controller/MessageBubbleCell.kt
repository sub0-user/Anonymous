package org.server.anonymous.controller

import javafx.fxml.FXML
import javafx.fxml.FXMLLoader
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.ContextMenu
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.MenuItem
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.layout.HBox
import javafx.scene.text.Text
import javafx.scene.text.TextFlow
import org.server.anonymous.business.model.MessageDirection
import org.server.anonymous.business.model.MessageItem
import org.server.anonymous.business.model.MessageStatus
import org.server.anonymous.business.model.ReplyRef
import java.util.ResourceBundle

/**
 * Renders one message bubble from message-bubble-cell.fxml.
 * Received room invites (the app's own invite format) get a one-tap "Accept invite" button,
 * and every bubble gets a right-click Copy menu.
 */
class MessageBubbleCell(
    private val onJoinInvite: (String) -> Unit = {},
    private val onReply: (MessageItem) -> Unit = {},
    private val replyName: (ReplyRef) -> String = { ref -> ref.senderName ?: "" },
) : ListCell<MessageItem>() {
    @FXML private lateinit var bubble: HBox

    @FXML internal lateinit var bodyFlow: TextFlow // internal: asserted by MessageBubbleCellTest

    @FXML private lateinit var metaLabel: Label

    @FXML internal lateinit var joinButton: Button // internal: fired directly by MessageBubbleCellTest

    private val rootNode: HBox

    private var currentInvite: String? = null

    private var currentBody: String? = null

    private var currentMessage: MessageItem? = null

    init {
        val bundle = ResourceBundle.getBundle("org.server.anonymous.messages")
        val loader = FXMLLoader(MessageBubbleCell::class.java.getResource("message-bubble-cell.fxml"), bundle)
        loader.setController(this)
        rootNode = loader.load<HBox>()
        val copyItem = MenuItem(bundle.getString("chat.copy"))
        copyItem.setOnAction {
            currentBody?.let { body ->
                Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putString(body) })
            }
        }
        val replyItem = MenuItem(bundle.getString("chat.reply"))
        replyItem.setOnAction {
            currentMessage?.let { onReply(it) }
        }
        contextMenu = ContextMenu(copyItem, replyItem)
        joinButton.setOnAction { currentInvite?.let(onJoinInvite) }
    }

    override fun updateItem(
        item: MessageItem?,
        empty: Boolean,
    ) {
        super.updateItem(item, empty)
        if (item == null || empty) {
            graphic = null
            currentBody = null
            currentMessage = null
            return
        }
        currentBody = item.body
        currentMessage = item
        val nodes = mutableListOf<Node>()
        item.replyTo?.let { ref -> nodes += replyNodes(ref) }
        nodes += EmojiImages.nodesFor(item.body)
        bodyFlow.children.setAll(nodes)
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

    /** The quoted "↩ name: preview" header shown above a reply's own text. */
    private fun replyNodes(ref: ReplyRef): List<Node> {
        val quote = Text("↩ ${replyName(ref)}: ${ref.text}\n")
        quote.styleClass.add("reply-quote")
        return listOf(quote)
    }

    private fun String.isRoomInvite(): Boolean = startsWith("inv4p:") || startsWith("inv4u:")
}
