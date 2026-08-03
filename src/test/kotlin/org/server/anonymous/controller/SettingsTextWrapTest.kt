package org.server.anonymous.controller

import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.util.Callback
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.server.anonymous.AnonymousApplication
import org.server.anonymous.ui.JavaFxTestSupport
import java.util.ResourceBundle

/**
 * The settings rules/guide used to clip long lines with "…" because the labels did not fill
 * the box. This lays the view out at a narrow width and checks every wrapped label is
 * rendered at its full wrapped height (an ellipsized label renders one line shorter).
 */
class SettingsTextWrapTest {
    @Test
    fun `settings rules and guide wrap instead of truncating`() =
        JavaFxTestSupport.onFxThread {
            val bundle = ResourceBundle.getBundle("org.server.anonymous.messages")
            val loader = FXMLLoader(SettingsTextWrapTest::class.java.getResource("settings-view.fxml"), bundle)
            loader.controllerFactory =
                Callback { SettingsController(SettingsViewModel(FakeNodeStatusSource())) }
            val root = loader.load<Parent>()
            val scene = Scene(root, 480.0, 900.0)
            scene.stylesheets.add(AnonymousApplication.stylesheet())
            root.applyCss()
            root.layout()
            val truncated = mutableListOf<String>()
            root.lookupAll(".muted-text").forEach { node ->
                val label = node as? Label ?: return@forEach
                if (label.isWrapText && label.text.length > 40 && label.height < label.prefHeight(label.width) - 1.0) {
                    truncated += label.text.take(30) + "…"
                }
            }
            assertTrue(truncated.isEmpty(), "labels still truncate: $truncated")
        }
}
