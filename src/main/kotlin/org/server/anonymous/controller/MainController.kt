package org.server.anonymous.controller

import javafx.event.ActionEvent
import javafx.fxml.FXML
import javafx.fxml.FXMLLoader
import javafx.scene.Node
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
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
    private var chatViewModel: ChatViewModel? = null

    @Suppress("UnusedPrivateMember") // invoked reflectively by FXML
    @FXML
    private fun initialize() {
        searchField.textProperty().bindBidirectional(chatListViewModel.searchQuery)
        chatListView.items = chatListViewModel.filteredContacts
        chatListView.setCellFactory { ContactCell() }
        chatListView.selectionModel.selectedItemProperty().addListener { _, _, selected ->
            if (selected != null) showChat(selected)
        }
        showIdentity()
    }

    @FXML
    fun onAddContactClicked() {
        val viewModel = AddContactViewModel(appGraph.contactService)
        val dialog = Dialog<Contact>()
        val loader = FXMLLoader(MainController::class.java.getResource("add-contact-dialog.fxml"))
        loader.controllerFactory = Callback { AddContactDialogController(viewModel) }
        dialog.dialogPane = loader.load()
        dialog.title = "Add contact"
        val addType = ButtonType("Add", ButtonBar.ButtonData.OK_DONE)
        dialog.dialogPane.buttonTypes.add(addType)
        val addButton = dialog.dialogPane.lookupButton(addType)
        addButton.addEventFilter(ActionEvent.ACTION) { event ->
            viewModel.add()
            if (viewModel.addedContact.get() == null) {
                event.consume()
            }
        }
        dialog.setResultConverter { _ -> viewModel.addedContact.get() }
        dialog.showAndWait().ifPresent { added ->
            chatListViewModel.refresh()
            chatListView.selectionModel.select(added)
        }
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

    private fun showChat(contact: Contact) {
        val viewModel = ChatViewModel(appGraph.messageService, contact)
        chatViewModel = viewModel
        val view: Node = load("chat-view.fxml") { ChatController(viewModel) }
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
