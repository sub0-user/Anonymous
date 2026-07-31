package org.server.anonymous.controller

import javafx.beans.binding.Bindings
import javafx.fxml.FXML
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ListView
import javafx.scene.control.TextField
import org.server.anonymous.business.model.MessageItem

class ChatController(
    private val viewModel: ChatViewModel,
) {
    @FXML private lateinit var titleLabel: Label

    @FXML private lateinit var subtitleLabel: Label

    @FXML private lateinit var messageList: ListView<MessageItem>

    @FXML private lateinit var draftField: TextField

    @FXML private lateinit var sendButton: Button

    @Suppress("UnusedPrivateMember") // invoked reflectively by FXML
    @FXML
    private fun initialize() {
        titleLabel.textProperty().bind(viewModel.title)
        subtitleLabel.textProperty().bind(viewModel.subtitle)
        messageList.items = viewModel.messages
        messageList.setCellFactory { MessageBubbleCell() }
        draftField.textProperty().bindBidirectional(viewModel.draft)
        sendButton.disableProperty().bind(
            Bindings.createBooleanBinding(
                { draftField.text.isNullOrBlank() },
                draftField.textProperty(),
            ),
        )
        messageList.scrollTo((viewModel.messages.size - 1).coerceAtLeast(0))
    }

    @FXML
    fun onSendClicked() {
        viewModel.send()
        messageList.scrollTo((viewModel.messages.size - 1).coerceAtLeast(0))
    }
}
