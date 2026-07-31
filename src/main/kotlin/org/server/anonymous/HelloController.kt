package org.server.anonymous

import javafx.fxml.FXML
import javafx.scene.control.Label

class HelloController {
    @FXML
    private lateinit var welcomeText: Label

    @FXML
    @Suppress("UnusedPrivateMember") // invoked reflectively from hello-view.fxml
    private fun onHelloButtonClick() {
        welcomeText.text = "Welcome to JavaFX Application!"
    }
}
