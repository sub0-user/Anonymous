package org.server.anonymous.controller

import javafx.fxml.FXML
import javafx.scene.control.Label
import java.awt.Desktop
import java.net.URI

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

    @FXML
    fun onXClicked() = openUrl("https://x.com/Sub0_User")

    @FXML
    fun onGitHubClicked() = openUrl("https://github.com/sub0-user/")

    @FXML
    fun onYouTubeClicked() = openUrl("https://www.youtube.com/@Sub0-User")

    @FXML
    fun onInstagramClicked() = openUrl("https://www.instagram.com/sub0_user/")

    @FXML
    fun onDonateClicked() = openUrl("https://www.paypal.com/ncp/payment/PMEKPE9SURFBE")

    /**
     * Opens the link in the user's own browser on an explicit button click — the app itself
     * never fetches anything; the user chooses to leave.
     */
    private fun openUrl(url: String) {
        runCatching { Desktop.getDesktop().browse(URI(url)) }
    }
}
