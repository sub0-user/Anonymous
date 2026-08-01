package org.server.anonymous.controller

import javafx.beans.binding.Bindings
import javafx.fxml.FXML
import javafx.scene.control.Label
import javafx.scene.control.ListView
import org.server.anonymous.business.model.ContactRequest

class RequestsController(
    private val viewModel: RequestsViewModel,
) {
    @FXML private lateinit var requestList: ListView<ContactRequest>

    @FXML private lateinit var emptyLabel: Label

    @FXML private lateinit var feedbackLabel: Label

    @Suppress("UnusedPrivateMember") // invoked reflectively by FXML
    @FXML
    private fun initialize() {
        requestList.items = viewModel.requests
        requestList.setCellFactory { RequestsCell() }
        emptyLabel.visibleProperty().bind(Bindings.isEmpty(viewModel.requests))
        feedbackLabel.textProperty().bind(viewModel.message)
    }

    @FXML
    fun onAcceptClicked() {
        requestList.selectionModel.selectedItem?.let { viewModel.accept(it) }
    }

    @FXML
    fun onIgnoreClicked() {
        requestList.selectionModel.selectedItem?.let { viewModel.ignore(it) }
    }

    @FXML
    fun onBlockClicked() {
        requestList.selectionModel.selectedItem?.let { viewModel.block(it) }
    }
}
