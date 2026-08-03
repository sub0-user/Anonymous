package org.server.anonymous.controller

import javafx.event.ActionEvent
import javafx.fxml.FXML
import javafx.fxml.FXMLLoader
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.ListView
import javafx.scene.control.TextField
import javafx.util.Callback
import org.server.anonymous.AnonymousApplication
import org.server.anonymous.business.model.RoomMessageItem
import java.util.ResourceBundle

/** Room chat: header (name, type, member count), founder actions, composer, message list. */
class RoomChatController(
    private val viewModel: RoomChatViewModel,
    private val onRoomLeft: () -> Unit = {},
) {
    @FXML private lateinit var titleLabel: Label

    @FXML private lateinit var subtitleLabel: Label

    @FXML private lateinit var messageList: ListView<RoomMessageItem>

    @FXML private lateinit var draftField: TextField

    @FXML private lateinit var inviteButton: Button

    @FXML private lateinit var membersButton: Button

    @FXML private lateinit var feedbackLabel: Label

    @FXML private lateinit var inviteFeedbackLabel: Label

    @Suppress("UnusedPrivateProperty") // injected by FXML; always visible (no binding needed)
    @FXML
    private lateinit var leaveButton: Button

    @FXML private lateinit var deleteButton: Button

    @FXML private lateinit var emojiButton: Button

    private val bundle = ResourceBundle.getBundle("org.server.anonymous.messages")

    @Suppress("UnusedPrivateMember") // invoked reflectively by FXML
    @FXML
    private fun initialize() {
        titleLabel.textProperty().bind(viewModel.title)
        subtitleLabel.textProperty().bind(viewModel.subtitle)
        feedbackLabel.textProperty().bind(viewModel.sendFeedback)
        inviteFeedbackLabel.textProperty().bind(viewModel.inviteFeedback)
        messageList.items = viewModel.messages
        messageList.setCellFactory { RoomMessageCell(viewModel::displayNameFor) }
        draftField.textProperty().bindBidirectional(viewModel.draft)
        inviteButton.visibleProperty().bind(viewModel.founderVisible)
        inviteButton.managedProperty().bind(viewModel.founderVisible)
        membersButton.visibleProperty().bind(viewModel.founderVisible)
        membersButton.managedProperty().bind(viewModel.founderVisible)
        deleteButton.visibleProperty().bind(viewModel.founderVisible)
        deleteButton.managedProperty().bind(viewModel.founderVisible)
        messageList.scrollTo((viewModel.messages.size - 1).coerceAtLeast(0))
    }

    @FXML
    fun onSendClicked() {
        viewModel.send()
        messageList.scrollTo((viewModel.messages.size - 1).coerceAtLeast(0))
    }

    @FXML
    fun onEmojiClicked() {
        EmojiPicker.show(emojiButton) { emoji ->
            val caret = draftField.caretPosition
            draftField.insertText(caret, emoji)
            draftField.requestFocus()
            draftField.positionCaret(caret + emoji.length)
        }
    }

    @FXML
    fun onInviteClicked() {
        val dialog = Dialog<String>()
        val loader = FXMLLoader(RoomChatController::class.java.getResource("invite-dialog.fxml"), bundle)
        loader.controllerFactory = Callback { InviteDialogController(viewModel) }
        dialog.dialogPane = loader.load()
        dialog.dialogPane.stylesheets.add(AnonymousApplication.stylesheet())
        dialog.title = bundle.getString("room.invite")
        val createType = ButtonType(bundle.getString("dialog.create"), ButtonBar.ButtonData.OK_DONE)
        dialog.dialogPane.buttonTypes.add(createType)
        val controller = loader.getController<InviteDialogController>()
        val createButton = dialog.dialogPane.lookupButton(createType)
        createButton.disableProperty().bind(controller.busy)
        createButton.addEventFilter(ActionEvent.ACTION) { event ->
            event.consume() // publishing the invite can take a minute — close only when it is done
            controller.createAsync {
                if (controller.createdInvite != null) {
                    dialog.setResult(controller.createdInvite)
                    dialog.hide()
                }
            }
        }
        dialog.setResultConverter { controller.createdInvite }
        dialog.showAndWait()
        controller.outcome?.let { invite ->
            val sent = bundle.getString("room.invite.sent").replace("{alias}", invite.contactAlias)
            val copied = bundle.getString("room.invite.copy_only").replace("{alias}", invite.contactAlias)
            viewModel.inviteFeedback.set(
                if (invite.delivered) sent else copied.replace("{reason}", invite.sendError ?: "?"),
            )
        }
    }

    @FXML
    fun onMembersClicked() {
        val dialog = Dialog<Void>()
        val loader = FXMLLoader(RoomChatController::class.java.getResource("members-dialog.fxml"), bundle)
        loader.controllerFactory = Callback { MembersDialogController(viewModel) }
        dialog.dialogPane = loader.load()
        dialog.dialogPane.stylesheets.add(AnonymousApplication.stylesheet())
        dialog.title = bundle.getString("room.members")
        dialog.showAndWait()
        viewModel.syncAfterDialog()
    }

    @FXML
    fun onClearHistoryClicked() {
        viewModel.clearHistory()
    }

    @FXML
    fun onLeaveClicked() {
        val message = bundle.getString("room.leave.confirm").replace("{room}", viewModel.title.get())
        val dialog = Alert(Alert.AlertType.CONFIRMATION, message)
        dialog.title = bundle.getString("room.leave")
        val confirmed = dialog.showAndWait().filter { it == ButtonType.OK }.isPresent
        if (confirmed && viewModel.leaveRoom()) onRoomLeft()
    }

    @FXML
    fun onDeleteClicked() {
        val message = bundle.getString("room.delete.confirm").replace("{room}", viewModel.title.get())
        val dialog = Alert(Alert.AlertType.CONFIRMATION, message)
        dialog.title = bundle.getString("room.delete")
        val confirmed = dialog.showAndWait().filter { it == ButtonType.OK }.isPresent
        if (confirmed && viewModel.deleteRoom()) onRoomLeft()
    }
}
