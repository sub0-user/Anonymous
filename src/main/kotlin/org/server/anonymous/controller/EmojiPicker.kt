package org.server.anonymous.controller

import javafx.fxml.FXMLLoader
import javafx.scene.Node
import javafx.scene.Parent
import javafx.stage.Popup
import javafx.util.Callback
import org.server.anonymous.AnonymousApplication
import java.util.ResourceBundle

/** Shows the bundled emoji strip just above the composer and forwards the picked emoji. */
object EmojiPicker {
    fun show(
        anchor: Node,
        onPick: (String) -> Unit,
    ) {
        val bundle = ResourceBundle.getBundle("org.server.anonymous.messages")
        val loader = FXMLLoader(EmojiPicker::class.java.getResource("emoji-picker.fxml"), bundle)
        loader.controllerFactory = Callback { EmojiPickerController(onPick) }
        val root = loader.load<Parent>()
        root.stylesheets.add(AnonymousApplication.stylesheet())
        val popup = Popup()
        popup.setAutoFix(true)
        popup.setAutoHide(true)
        popup.content.add(root)
        val point = anchor.localToScreen(0.0, 0.0)
        popup.show(anchor.scene.window, point.x, point.y - 64.0)
    }
}
