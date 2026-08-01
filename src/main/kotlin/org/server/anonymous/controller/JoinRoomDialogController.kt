package org.server.anonymous.controller

import javafx.fxml.FXML
import javafx.scene.control.Label
import javafx.scene.control.TextArea
import javafx.scene.control.TextField

/** Join-room dialog: paste the invite + pick your display name. */
class JoinRoomDialogController(
    private val viewModel: JoinRoomViewModel,
) {
    @FXML private lateinit var inviteField: TextArea

    @FXML private lateinit var myNameField: TextField

    @FXML private lateinit var feedbackLabel: Label

    @Suppress("UnusedPrivateMember") // invoked reflectively by FXML
    @FXML
    private fun initialize() {
        inviteField.textProperty().bindBidirectional(viewModel.invite)
        myNameField.textProperty().bindBidirectional(viewModel.myName)
        feedbackLabel.textProperty().bind(viewModel.feedback)
    }
}
