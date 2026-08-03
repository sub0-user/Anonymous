package org.server.anonymous.controller

import javafx.scene.text.Text
import javafx.scene.text.TextFlow
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.server.anonymous.ui.JavaFxTestSupport

/**
 * This project's JavaFX build has no `wrapText` toggle on TextFlow — it wraps at the layout
 * width by default. The bubble relies on that; this guards against a JavaFX swap breaking it.
 */
class TextFlowTest {
    @Test
    fun `text flow wraps at narrow width`() =
        JavaFxTestSupport.onFxThread {
            val flow = TextFlow(Text("word ".repeat(100)))
            val narrow = flow.prefHeight(200.0)
            val wide = flow.prefHeight(2000.0)
            assertTrue(narrow > wide * 1.5, "expected wrapping: narrow=$narrow wide=$wide")
        }
}
