package org.server.anonymous.controller

import javafx.fxml.FXML
import javafx.scene.control.Label
import javafx.scene.control.TextField

/** Small text prompt (rename): hint label + a single text field. */
class NameDialogController(
    private val hint: String,
) {
    @FXML private lateinit var hintLabel: Label

    @FXML private lateinit var nameField: TextField

    val name: String
        get() = nameField.text.trim()

    @Suppress("UnusedPrivateMember") // invoked reflectively by FXML
    @FXML
    private fun initialize() {
        hintLabel.text = hint
    }
}
