package org.server.anonymous.controller

import javafx.fxml.FXML
import javafx.scene.control.Label
import javafx.scene.control.RadioButton
import javafx.scene.control.TextField
import org.server.anonymous.business.model.RoomType

/** New-room dialog: name + display name + private/public type. */
class NewRoomDialogController(
    private val viewModel: NewRoomViewModel,
) {
    @FXML private lateinit var nameField: TextField

    @FXML private lateinit var myNameField: TextField

    @FXML private lateinit var privateChoice: RadioButton

    @FXML private lateinit var publicChoice: RadioButton

    @FXML private lateinit var feedbackLabel: Label

    @FXML private lateinit var busyLabel: Label

    @Suppress("UnusedPrivateMember") // invoked reflectively by FXML
    @FXML
    private fun initialize() {
        nameField.textProperty().bindBidirectional(viewModel.name)
        myNameField.textProperty().bindBidirectional(viewModel.myName)
        privateChoice.selectedProperty().addListener { _, _, selected ->
            if (selected) viewModel.type.set(RoomType.PRIVATE)
        }
        publicChoice.selectedProperty().addListener { _, _, selected ->
            if (selected) viewModel.type.set(RoomType.PUBLIC)
        }
        feedbackLabel.textProperty().bind(viewModel.feedback)
        busyLabel.visibleProperty().bind(viewModel.busy)
        busyLabel.managedProperty().bind(viewModel.busy)
    }
}
