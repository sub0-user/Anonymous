package org.server.anonymous.controller

import javafx.fxml.FXML
import javafx.fxml.FXMLLoader
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.ContextMenu
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.MenuItem
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.layout.HBox
import javafx.scene.text.Text
import javafx.scene.text.TextFlow
import org.server.anonymous.business.model.ReplyRef
import org.server.anonymous.business.model.RoomMessageItem
import java.util.ResourceBundle

/** Renders one room message: "name · body" bubble with a time label, reusing the 1:1 bubble. */
class RoomMessageCell(
    private val displayNameFor: (ByteArray) -> String,
    private val onReply: (RoomMessageItem) -> Unit = {},
    private val replyName: (ReplyRef) -> String = { ref -> ref.senderName ?: "" },
) : ListCell<RoomMessageItem>() {
    @FXML private lateinit var bubble: HBox

    @FXML internal lateinit var bodyFlow: TextFlow // internal: asserted by the cell tests

    @FXML private lateinit var metaLabel: Label

    private val rootNode: HBox

    private var currentBody: String? = null

    private var currentMessage: RoomMessageItem? = null

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
        val replyItem = MenuItem(bundle.getString("chat.reply"))
        replyItem.setOnAction {
            currentMessage?.let { onReply(it) }
        }
        contextMenu = ContextMenu(copyItem, replyItem)
    }

    override fun updateItem(
        item: RoomMessageItem?,
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
        nodes += EmojiImages.nodesFor("${displayNameFor(item.senderPublicKey)}: ${item.body}")
        bodyFlow.children.setAll(nodes)
        metaLabel.text = item.timeLabel
        bubble.styleClass.removeAll("bubble-out", "bubble-in")
        bubble.styleClass.add(if (item.isOutgoing) "bubble-out" else "bubble-in")
        alignment = if (item.isOutgoing) Pos.CENTER_RIGHT else Pos.CENTER_LEFT
        graphic = rootNode
    }

    /** Test seam: renders [item] the way a real list would (ListCell.updateItem is protected). */
    internal fun render(item: RoomMessageItem?) = updateItem(item, false)

    /** The quoted "↩ name: preview" header shown above a reply's own text. */
    private fun replyNodes(ref: ReplyRef): List<Node> {
        val quote = Text("↩ ${replyName(ref)}: ${ref.text}\n")
        quote.styleClass.add("reply-quote")
        return listOf(quote)
    }
}
