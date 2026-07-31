package org.server.anonymous.controller

import javafx.fxml.FXML
import javafx.scene.control.ListView
import javafx.scene.control.TextField
import javafx.scene.layout.StackPane
import org.server.anonymous.business.AppGraph
import org.server.anonymous.business.model.Contact

class MainController(
    private val appGraph: AppGraph,
) {
    @FXML private lateinit var searchField: TextField

    @FXML private lateinit var chatListView: ListView<Contact>

    @Suppress("UnusedPrivateProperty") // injected by FXML; populated by the child views in later tasks
    @FXML
    private lateinit var contentStack: StackPane

    private val chatListViewModel = ChatListViewModel(appGraph.contactService)

    @Suppress("UnusedPrivateMember") // invoked reflectively by FXML
    @FXML
    private fun initialize() {
        searchField.textProperty().bindBidirectional(chatListViewModel.searchQuery)
        chatListView.items = chatListViewModel.filteredContacts
        chatListView.setCellFactory { ContactCell() }
    }

    @FXML
    fun onAddContactClicked() {
        // filled in Task 1.13 (dialog)
    }

    @FXML
    fun onShowIdentityClicked() {
        // filled in Task 1.11
    }

    @FXML
    fun onShowSettingsClicked() {
        // filled in Task 1.14
    }
}
