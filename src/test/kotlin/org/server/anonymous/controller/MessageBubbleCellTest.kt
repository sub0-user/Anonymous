package org.server.anonymous.controller

import javafx.scene.input.Clipboard
import javafx.scene.text.Text
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.server.anonymous.business.model.MessageDirection
import org.server.anonymous.business.model.MessageItem
import org.server.anonymous.business.model.MessageStatus
import org.server.anonymous.business.model.ReplyRef
import org.server.anonymous.ui.JavaFxTestSupport

class MessageBubbleCellTest {
    private fun item(
        direction: MessageDirection,
        body: String,
    ): MessageItem = MessageItem(1, direction, body, MessageStatus.DELIVERED, "10:00")

    @Test
    fun `join button appears on a received private invite`() =
        JavaFxTestSupport.onFxThread {
            val cell = MessageBubbleCell()
            cell.render(item(MessageDirection.IN, "inv4p:abc"))
            assertTrue(cell.joinButton.isVisible)
        }

    @Test
    fun `join button appears on a received public invite`() =
        JavaFxTestSupport.onFxThread {
            val cell = MessageBubbleCell()
            cell.render(item(MessageDirection.IN, "inv4u:abc"))
            assertTrue(cell.joinButton.isVisible)
        }

    @Test
    fun `join button is hidden for ordinary messages`() =
        JavaFxTestSupport.onFxThread {
            val cell = MessageBubbleCell()
            cell.render(item(MessageDirection.IN, "just saying hi"))
            assertFalse(cell.joinButton.isVisible)
        }

    @Test
    fun `join button is hidden on the founder's own sent invite`() =
        JavaFxTestSupport.onFxThread {
            val cell = MessageBubbleCell()
            cell.render(item(MessageDirection.OUT, "inv4p:abc"))
            assertFalse(cell.joinButton.isVisible)
        }

    @Test
    fun `clicking join forwards the invite text`() =
        JavaFxTestSupport.onFxThread {
            var captured: String? = null
            val cell = MessageBubbleCell(onJoinInvite = { captured = it })
            cell.render(item(MessageDirection.IN, "inv4p:xyz"))
            cell.joinButton.fire()
            assertEquals("inv4p:xyz", captured)
        }

    @Test
    fun `reply menu fires onReply with the message`() =
        JavaFxTestSupport.onFxThread {
            var replied: MessageItem? = null
            val cell = MessageBubbleCell(onReply = { replied = it })
            val target = item(MessageDirection.IN, "hello world")
            cell.render(target)
            cell.contextMenu.items[1].fire()
            assertEquals(target, replied)
        }

    @Test
    fun `reply bubble shows the quoted preview above the body`() =
        JavaFxTestSupport.onFxThread {
            val cell = MessageBubbleCell()
            val withReply =
                MessageItem(
                    1,
                    MessageDirection.IN,
                    "answer",
                    MessageStatus.DELIVERED,
                    "10:00",
                    ReplyRef(senderName = "raven", text = "the question"),
                )
            cell.render(withReply)
            val quote = cell.bodyFlow.children[0] as Text
            assertTrue(quote.styleClass.contains("reply-quote"))
            assertEquals("↩ raven: the question", quote.text.trim())
        }

    @Test
    fun `copy menu copies the message body`() =
        JavaFxTestSupport.onFxThread {
            val cell = MessageBubbleCell()
            cell.render(item(MessageDirection.IN, "hello world"))
            val menu = cell.contextMenu
            val copyItem = menu.items[0]
            copyItem.fire()
            assertEquals("hello world", Clipboard.getSystemClipboard().getString())
        }
}
