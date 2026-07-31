package org.server.anonymous.controller

import javafx.fxml.FXML
import javafx.scene.control.Label
import javafx.scene.control.TextField

class AddContactDialogController(
    private val viewModel: AddContactViewModel,
) {
    @FXML private lateinit var aliasField: TextField

    @FXML private lateinit var addressField: TextField

    @FXML private lateinit var feedbackLabel: Label

    @Suppress("UnusedPrivateMember") // invoked reflectively by FXML
    @FXML
    private fun initialize() {
        aliasField.textProperty().bindBidirectional(viewModel.alias)
        addressField.textProperty().bindBidirectional(viewModel.address)
        feedbackLabel.textProperty().bind(viewModel.feedback)
    }
}
