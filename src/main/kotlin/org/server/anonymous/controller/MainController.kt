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
import org.server.anonymous.AnonymousApplication
import org.server.anonymous.business.AppGraph
import org.server.anonymous.business.OpResult
import org.server.anonymous.business.model.Contact
import org.server.anonymous.business.model.RoomRecord
import java.util.ResourceBundle

/** Root navigation + wiring between views. */
@Suppress("TooManyFunctions") // navigation surface: one handler per sidebar action
class MainController(
    private val appGraph: AppGraph,
) {
    @FXML private lateinit var searchField: TextField

    @FXML private lateinit var chatListView: ListView<Contact>

    @FXML private lateinit var roomsListView: ListView<RoomRecord>

    @Suppress("UnusedPrivateProperty") // injected by FXML; populated by the child views
    @FXML
    private lateinit var contentStack: StackPane

    private val chatListViewModel = ChatListViewModel(appGraph.contactService)
    private val roomsListViewModel = RoomsListViewModel(appGraph.roomMessenger)
    private val requestsViewModel = RequestsViewModel(appGraph.contactService)
    private val identityViewModel =
        IdentityViewModel(
            appGraph.torNodeManager,
            appGraph.identityService,
            appGraph.contactService,
            appGraph.roomStore,
        )
    private val settingsViewModel = SettingsViewModel(appGraph.torNodeManager)
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
        // Re-clicking an already-selected row must re-open the chat (selection alone won't re-fire).
        chatListView.setOnMouseClicked {
            val selected = chatListView.selectionModel.selectedItem
            if (selected != null) showChat(selected)
        }
        roomsListView.items = roomsListViewModel.rooms
        roomsListView.setCellFactory { RoomCell() }
        roomsListView.selectionModel.selectedItemProperty().addListener { _, _, selected ->
            if (selected != null) showRoom(selected)
        }
        roomsListView.setOnMouseClicked {
            val selected = roomsListView.selectionModel.selectedItem
            if (selected != null) showRoom(selected)
        }
        showIdentity()
    }

    @FXML
    fun onAddContactClicked() {
        val bundle = ResourceBundle.getBundle("org.server.anonymous.messages")
        val viewModel = AddContactViewModel(appGraph.contactService)
        val dialog = Dialog<Contact>()
        val loader = FXMLLoader(MainController::class.java.getResource("add-contact-dialog.fxml"), bundle)
        loader.controllerFactory = Callback { AddContactDialogController(viewModel) }
        dialog.dialogPane = loader.load()
        dialog.dialogPane.stylesheets.add(AnonymousApplication.stylesheet())
        dialog.title = bundle.getString("dialog.add_contact")
        val addType = ButtonType(bundle.getString("dialog.add"), ButtonBar.ButtonData.OK_DONE)
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
    fun onNewRoomClicked() {
        val bundle = ResourceBundle.getBundle("org.server.anonymous.messages")
        val viewModel = NewRoomViewModel(appGraph.roomHost)
        val dialog = Dialog<RoomRecord>()
        val loader = FXMLLoader(MainController::class.java.getResource("new-room-dialog.fxml"), bundle)
        loader.controllerFactory = Callback { NewRoomDialogController(viewModel) }
        dialog.dialogPane = loader.load()
        dialog.dialogPane.stylesheets.add(AnonymousApplication.stylesheet())
        dialog.title = bundle.getString("rooms.new")
        val createType = ButtonType(bundle.getString("dialog.create"), ButtonBar.ButtonData.OK_DONE)
        dialog.dialogPane.buttonTypes.add(createType)
        val createButton = dialog.dialogPane.lookupButton(createType)
        createButton.disableProperty().bind(viewModel.busy)
        createButton.addEventFilter(ActionEvent.ACTION) { event ->
            event.consume() // publishing the room can take a minute — close only on success
            viewModel.createAsync {
                if (viewModel.result.get() is OpResult.Success) {
                    dialog.setResult((viewModel.result.get() as OpResult.Success).value)
                    dialog.hide()
                }
            }
        }
        dialog.setResultConverter { _ -> (viewModel.result.get() as? OpResult.Success)?.value }
        dialog.showAndWait().ifPresent { room ->
            roomsListViewModel.refresh()
            showRoom(room)
        }
    }

    /** Opens the accept-invite dialog with the invite pre-filled from the chat message. */
    private fun openJoinRoomDialog(prefill: String) {
        val bundle = ResourceBundle.getBundle("org.server.anonymous.messages")
        val viewModel = JoinRoomViewModel(appGraph.roomMessenger, prefill)
        val dialog = Dialog<RoomRecord>()
        val loader = FXMLLoader(MainController::class.java.getResource("join-room-dialog.fxml"), bundle)
        loader.controllerFactory = Callback { JoinRoomDialogController(viewModel) }
        dialog.dialogPane = loader.load()
        dialog.dialogPane.stylesheets.add(AnonymousApplication.stylesheet())
        dialog.title = bundle.getString("room.join.accept")
        val joinType = ButtonType(bundle.getString("dialog.join"), ButtonBar.ButtonData.OK_DONE)
        dialog.dialogPane.buttonTypes.add(joinType)
        val joinButton = dialog.dialogPane.lookupButton(joinType)
        joinButton.disableProperty().bind(viewModel.busy)
        joinButton.addEventFilter(ActionEvent.ACTION) { event ->
            // Joining contacts the room host over Tor — run it off the FX thread and close
            // via result when it succeeds, so an unreachable host never freezes the window.
            event.consume()
            viewModel.acceptAndJoin()
        }
        viewModel.result.addListener { _, _, new ->
            if (new is OpResult.Success) {
                dialog.setResult(new.value)
                dialog.hide()
            }
        }
        dialog.setResultConverter { _ -> (viewModel.result.get() as? OpResult.Success)?.value }
        dialog.showAndWait().ifPresent { room ->
            roomsListViewModel.refresh()
            showRoom(room)
        }
    }

    @FXML
    fun onShowRequestsClicked() {
        requestsViewModel.refresh()
        showRequests()
    }

    @FXML
    fun onShowIdentityClicked() {
        showIdentity()
    }

    @FXML
    fun onShowSettingsClicked() {
        showSettings()
    }

    private fun showRequests() {
        val view: Node = load("requests-view.fxml") { RequestsController(requestsViewModel) }
        swapContent(view)
    }

    private fun showSettings() {
        val view: Node = load("settings-view.fxml") { SettingsController(settingsViewModel) }
        swapContent(view)
    }

    private fun showIdentity() {
        val view: Node = load("identity-view.fxml") { IdentityController(identityViewModel) }
        swapContent(view)
    }

    private fun showChat(contact: Contact) {
        val viewModel =
            ChatViewModel(
                appGraph.messageService,
                appGraph.contactService,
                appGraph.torNodeManager,
                appGraph.identityService,
                contact,
            )
        chatViewModel = viewModel
        val view: Node =
            load("chat-view.fxml") {
                ChatController(
                    viewModel,
                    onContactDeleted = {
                        chatListViewModel.refresh()
                        showIdentity()
                    },
                    onJoinInvite = { invite -> openJoinRoomDialog(invite) },
                )
            }
        swapContent(view)
    }

    private fun showRoom(room: RoomRecord) {
        val viewModel =
            RoomChatViewModel(
                appGraph.roomMessenger,
                if (room.isFounder) appGraph.roomHost else null,
                room.id,
                { appGraph.contactService.listContacts() },
                appGraph.messageService,
            )
        val view: Node =
            load("room-chat-view.fxml") {
                RoomChatController(viewModel) {
                    roomsListViewModel.refresh()
                    showIdentity()
                }
            }
        swapContent(view)
    }

    private fun swapContent(view: Node) {
        contentStack.children.setAll(view)
    }

    private fun <T> load(
        fxml: String,
        factory: (Class<*>) -> Any,
    ): T {
        val bundle = ResourceBundle.getBundle("org.server.anonymous.messages")
        val loader = FXMLLoader(MainController::class.java.getResource(fxml), bundle)
        loader.controllerFactory = Callback { factory(it) }
        return loader.load()
    }
}
