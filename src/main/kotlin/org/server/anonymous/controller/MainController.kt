package org.server.anonymous.controller

import javafx.fxml.FXML
import javafx.fxml.FXMLLoader
import javafx.scene.Node
import javafx.scene.control.ListView
import javafx.scene.control.TextField
import javafx.scene.layout.StackPane
import javafx.util.Callback
import org.server.anonymous.business.AppGraph
import org.server.anonymous.business.model.Contact

class MainController(
    private val appGraph: AppGraph,
) {
    @FXML private lateinit var searchField: TextField

    @FXML private lateinit var chatListView: ListView<Contact>

    @Suppress("UnusedPrivateProperty") // injected by FXML; populated by the child views
    @FXML
    private lateinit var contentStack: StackPane

    private val chatListViewModel = ChatListViewModel(appGraph.contactService)
    private val identityViewModel = IdentityViewModel()

    @Suppress("UnusedPrivateMember") // invoked reflectively by FXML
    @FXML
    private fun initialize() {
        searchField.textProperty().bindBidirectional(chatListViewModel.searchQuery)
        chatListView.items = chatListViewModel.filteredContacts
        chatListView.setCellFactory { ContactCell() }
        showIdentity()
    }

    @FXML
    fun onAddContactClicked() {
        // filled in Task 1.13 (dialog)
    }

    @FXML
    fun onShowIdentityClicked() {
        showIdentity()
    }

    @FXML
    fun onShowSettingsClicked() {
        // filled in Task 1.14
    }

    private fun showIdentity() {
        val view: Node = load("identity-view.fxml") { IdentityController(identityViewModel) }
        swapContent(view)
    }

    private fun swapContent(view: Node) {
        contentStack.children.setAll(view)
    }

    private fun <T> load(
        fxml: String,
        factory: (Class<*>) -> Any,
    ): T {
        val loader = FXMLLoader(MainController::class.java.getResource(fxml))
        loader.controllerFactory = Callback { factory(it) }
        return loader.load()
    }
}
