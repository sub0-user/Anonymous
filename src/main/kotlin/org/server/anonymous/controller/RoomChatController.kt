package org.server.anonymous.controller

import javafx.collections.ListChangeListener
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
import javafx.scene.layout.HBox
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

    @FXML private lateinit var replyBar: HBox

    @FXML private lateinit var replyBarLabel: Label

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
        messageList.setCellFactory {
            RoomMessageCell(viewModel::displayNameFor, onReply = viewModel::replyTo, replyName = viewModel::replyName)
        }
        draftField.textProperty().bindBidirectional(viewModel.draft)
        replyBar.visibleProperty().bind(viewModel.replyingTo.isNotNull)
        replyBar.managedProperty().bind(viewModel.replyingTo.isNotNull)
        replyBarLabel.textProperty().bind(viewModel.replyBarLabel)
        inviteButton.visibleProperty().bind(viewModel.founderVisible)
        inviteButton.managedProperty().bind(viewModel.founderVisible)
        membersButton.visibleProperty().bind(viewModel.founderVisible)
        membersButton.managedProperty().bind(viewModel.founderVisible)
        deleteButton.visibleProperty().bind(viewModel.founderVisible)
        deleteButton.managedProperty().bind(viewModel.founderVisible)
        // Sends are delivered off the FX thread, so scroll when the list actually changes.
        viewModel.messages.addListener(
            ListChangeListener<RoomMessageItem> {
                messageList.scrollTo((viewModel.messages.size - 1).coerceAtLeast(0))
            },
        )
        messageList.scrollTo((viewModel.messages.size - 1).coerceAtLeast(0))
    }

    @FXML
    fun onSendClicked() {
        viewModel.send()
        messageList.scrollTo((viewModel.messages.size - 1).coerceAtLeast(0))
    }

    @FXML
    fun onCancelReplyClicked() {
        viewModel.clearReply()
        draftField.requestFocus()
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
    fun onAddMemberClicked() {
        val dialog = Dialog<String>()
        val loader = FXMLLoader(RoomChatController::class.java.getResource("add-member-dialog.fxml"), bundle)
        loader.controllerFactory = Callback { AddMemberDialogController(viewModel) }
        dialog.dialogPane = loader.load()
        dialog.dialogPane.stylesheets.add(AnonymousApplication.stylesheet())
        dialog.title = bundle.getString("room.add_member")
        val addType = ButtonType(bundle.getString("dialog.add"), ButtonBar.ButtonData.OK_DONE)
        dialog.dialogPane.buttonTypes.add(addType)
        val controller = loader.getController<AddMemberDialogController>()
        val addButton = dialog.dialogPane.lookupButton(addType)
        addButton.disableProperty().bind(controller.busy)
        addButton.addEventFilter(ActionEvent.ACTION) { event ->
            event.consume() // the key exchange can take a moment — close only when the invite is sent
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
            viewModel.inviteFeedback.set(
                if (invite.delivered) {
                    bundle.getString("room.add.sent").replace("{alias}", invite.contactAlias)
                } else {
                    val failed = bundle.getString("room.add.failed")
                    failed.replace("{alias}", invite.contactAlias).replace("{reason}", invite.sendError ?: "?")
                },
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
        if (confirmed) viewModel.leaveRoom { ok -> if (ok) onRoomLeft() }
    }

    @FXML
    fun onDeleteClicked() {
        val message = bundle.getString("room.delete.confirm").replace("{room}", viewModel.title.get())
        val dialog = Alert(Alert.AlertType.CONFIRMATION, message)
        dialog.title = bundle.getString("room.delete")
        val confirmed = dialog.showAndWait().filter { it == ButtonType.OK }.isPresent
        if (confirmed) viewModel.deleteRoom { ok -> if (ok) onRoomLeft() }
    }
}
