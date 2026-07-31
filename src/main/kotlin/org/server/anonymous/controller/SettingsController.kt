package org.server.anonymous.controller

import javafx.fxml.FXML
import javafx.scene.control.Label

class SettingsController(
    private val viewModel: SettingsViewModel,
) {
    @FXML private lateinit var nodeStatusLabel: Label

    @FXML private lateinit var dataDirectoryLabel: Label

    @FXML private lateinit var versionLabel: Label

    @Suppress("UnusedPrivateMember") // invoked reflectively by FXML
    @FXML
    private fun initialize() {
        nodeStatusLabel.textProperty().bind(viewModel.nodeStatus)
        dataDirectoryLabel.textProperty().bind(viewModel.dataDirectory)
        versionLabel.textProperty().bind(viewModel.versionLabel)
    }
}
