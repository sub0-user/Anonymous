package org.server.anonymous.controller

import javafx.fxml.FXML
import javafx.scene.control.Label
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent

class IdentityController(
    private val viewModel: IdentityViewModel,
) {
    @FXML private lateinit var addressLabel: Label

    @FXML private lateinit var nodeStatusLabel: Label

    @FXML private lateinit var dataDirectoryLabel: Label

    @FXML private lateinit var versionLabel: Label

    @FXML private lateinit var logoImage: ImageView

    @Suppress("UnusedPrivateMember") // invoked reflectively by FXML
    @FXML
    private fun initialize() {
        addressLabel.textProperty().bind(viewModel.onionAddress)
        nodeStatusLabel.textProperty().bind(viewModel.nodeStatus)
        dataDirectoryLabel.textProperty().bind(viewModel.dataDirectory)
        versionLabel.textProperty().bind(viewModel.versionLabel)
        logoImage.image =
            Image(
                IdentityController::class.java.getResourceAsStream("/org/server/anonymous/logo/logo.png"),
            )
    }

    @FXML
    fun onCopyAddress() {
        val content = ClipboardContent()
        content.putString(viewModel.onionAddress.get())
        Clipboard.getSystemClipboard().setContent(content)
    }
}
