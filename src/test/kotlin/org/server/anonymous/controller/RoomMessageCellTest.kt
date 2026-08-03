package org.server.anonymous.controller

import javafx.scene.text.Text
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.server.anonymous.business.model.ReplyRef
import org.server.anonymous.business.model.RoomMessageItem
import org.server.anonymous.ui.JavaFxTestSupport

class RoomMessageCellTest {
    private fun item(replyTo: ReplyRef? = null): RoomMessageItem =
        RoomMessageItem(1, 2, ByteArray(32) { 3 }, "answer", "10:00", isOutgoing = false, replyTo = replyTo)

    @Test
    fun `reply menu fires onReply with the room message`() =
        JavaFxTestSupport.onFxThread {
            var replied: RoomMessageItem? = null
            val cell = RoomMessageCell({ "neo" }, onReply = { replied = it })
            val target = item()
            cell.render(target)
            cell.contextMenu.items[1].fire()
            assertEquals(target, replied)
        }

    @Test
    fun `reply header names the quoted author`() =
        JavaFxTestSupport.onFxThread {
            val cell = RoomMessageCell({ "neo" }, replyName = { "neo" })
            cell.render(item(ReplyRef(ByteArray(32) { 3 }, "neo", "the question")))
            val quote = cell.bodyFlow.children[0] as Text
            assertTrue(quote.styleClass.contains("reply-quote"))
            assertEquals("↩ neo: the question", quote.text.trim())
        }
}
