package org.server.anonymous.controller

import javafx.event.ActionEvent
import javafx.fxml.FXML
import javafx.fxml.FXMLLoader
import javafx.scene.control.Alert
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.PasswordField
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.stage.FileChooser
import javafx.util.Callback
import org.server.anonymous.AnonymousApplication
import org.server.anonymous.business.OpResult
import java.nio.file.Files
import java.util.ResourceBundle

class IdentityController(
    private val viewModel: IdentityViewModel,
) {
    @FXML private lateinit var addressLabel: Label

    @FXML private lateinit var nodeStatusLabel: Label

    @FXML private lateinit var dataDirectoryLabel: Label

    @FXML private lateinit var versionLabel: Label

    @FXML private lateinit var logoImage: ImageView

    @FXML private lateinit var backupMessageLabel: Label

    private val bundle = ResourceBundle.getBundle("org.server.anonymous.messages")

    @Suppress("UnusedPrivateMember") // invoked reflectively by FXML
    @FXML
    private fun initialize() {
        addressLabel.textProperty().bind(viewModel.onionAddress)
        nodeStatusLabel.textProperty().bind(viewModel.nodeStatus)
        dataDirectoryLabel.textProperty().bind(viewModel.dataDirectory)
        versionLabel.textProperty().bind(viewModel.versionLabel)
        backupMessageLabel.textProperty().bind(viewModel.backupMessage)
        logoImage.image =
            Image(
                IdentityController::class.java.getResourceAsStream("/org/server/anonymous/logo/icon.png"),
            )
    }

    @FXML
    fun onCopyAddress() {
        val content = ClipboardContent()
        content.putString(viewModel.onionAddress.get())
        Clipboard.getSystemClipboard().setContent(content)
    }

    @FXML
    fun onBackupClicked() {
        val passphrase = askPassphrase(bundle.getString("dialog.passphrase.export.hint")) ?: return
        when (val result = viewModel.exportIdentity(passphrase)) {
            is OpResult.Failure -> viewModel.backupMessage.set(result.reason)
            is OpResult.Success -> {
                val chooser = FileChooser().apply { title = bundle.getString("identity.backup") }
                val file = chooser.showSaveDialog(logoImage.scene.window) ?: return
                runCatching { Files.write(file.toPath(), result.value) }
                    .onFailure { viewModel.backupMessage.set(it.message ?: "Write failed") }
                    .onSuccess {
                        viewModel.backupMessage.set(bundle.getString("identity.backup.done") + " " + file.absolutePath)
                    }
            }
        }
    }

    @FXML
    fun onRestoreClicked() {
        val file = pickBackupFile() ?: return
        val data = readBackupFile(file) ?: return
        restoreFrom(data)
    }

    private fun restoreFrom(data: ByteArray) {
        val passphrase = askPassphrase(bundle.getString("dialog.passphrase.import.hint")) ?: return
        if (!confirmRestore()) return
        when (val result = viewModel.importIdentity(data, passphrase)) {
            is OpResult.Failure -> viewModel.backupMessage.set(result.reason)
            is OpResult.Success -> viewModel.backupMessage.set(bundle.getString("identity.restore.done"))
        }
    }

    private fun pickBackupFile(): java.io.File? {
        val chooser = FileChooser().apply { title = bundle.getString("identity.restore") }
        return chooser.showOpenDialog(logoImage.scene.window)
    }

    private fun readBackupFile(file: java.io.File): ByteArray? =
        runCatching { Files.readAllBytes(file.toPath()) }
            .getOrElse {
                viewModel.backupMessage.set(it.message ?: "Read failed")
                null
            }

    private fun confirmRestore(): Boolean =
        Alert(Alert.AlertType.CONFIRMATION, bundle.getString("identity.restore.confirm"))
            .apply { initOwner(logoImage.scene.window) }
            .showAndWait()
            .filter { it == ButtonType.OK }
            .isPresent

    /** Small passphrase prompt built from passphrase-dialog.fxml; returns null when cancelled. */
    private fun askPassphrase(hint: String): CharArray? {
        val dialog = Dialog<CharArray>()
        val controller = PassphraseDialogController(hint)
        val loader = FXMLLoader(IdentityController::class.java.getResource("passphrase-dialog.fxml"), bundle)
        loader.controllerFactory = Callback { controller }
        dialog.dialogPane = loader.load()
        dialog.dialogPane.stylesheets.add(AnonymousApplication.stylesheet())
        dialog.title = bundle.getString("dialog.passphrase")
        val okType = ButtonType(bundle.getString("dialog.passphrase.ok"), ButtonBar.ButtonData.OK_DONE)
        dialog.dialogPane.buttonTypes.add(okType)
        dialog.dialogPane.lookupButton(okType).addEventFilter(ActionEvent.ACTION) { event ->
            if (controller.passphrase.isEmpty()) event.consume()
        }
        dialog.setResultConverter { controller.passphrase }
        return dialog.showAndWait().orElse(null)
    }
}

/** Binds the passphrase prompt's hint text; exposes the typed passphrase. */
class PassphraseDialogController(
    private val hint: String,
) {
    @FXML private lateinit var passphraseField: PasswordField

    @FXML private lateinit var hintLabel: Label

    val passphrase: CharArray
        get() = passphraseField.text.toCharArray()

    @Suppress("UnusedPrivateMember") // invoked reflectively by FXML
    @FXML
    private fun initialize() {
        hintLabel.text = hint
    }
}
