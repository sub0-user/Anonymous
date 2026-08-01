package org.server.anonymous.controller

import javafx.application.Platform
import javafx.beans.property.SimpleBooleanProperty
import javafx.fxml.FXML
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.TextField
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import org.server.anonymous.business.OpResult
import org.server.anonymous.business.model.Contact
import java.util.ResourceBundle
import java.util.concurrent.Executors

/** Invite dialog (founder): pick a contact, their room name, optional expiry, create + copy. */
class InviteDialogController(
    private val viewModel: RoomChatViewModel,
) {
    @FXML private lateinit var contactBox: ComboBox<Contact>

    @FXML private lateinit var nameField: TextField

    @FXML private lateinit var expiryField: TextField

    @FXML private lateinit var inviteLabel: Label

    @FXML private lateinit var feedbackLabel: Label

    @FXML private lateinit var busyLabel: Label

    private val bundle = ResourceBundle.getBundle("org.server.anonymous.messages")

    /** Set when the invite was created; the dialog closes only then. */
    var createdInvite: String? = null

    /** True while the invite is being published (re-publish can take a minute) — Phase B3. */
    val busy = SimpleBooleanProperty(false)

    private val executor =
        Executors.newSingleThreadExecutor { r -> Thread(r, "invite-create").apply { isDaemon = true } }

    @Suppress("UnusedPrivateMember") // invoked reflectively by FXML
    @FXML
    private fun initialize() {
        val contacts = viewModel.contactsForInvite()
        contactBox.items.setAll(contacts)
        contactBox.setCellFactory { contactCell() }
        contactBox.buttonCell = contactCell()
        if (contacts.isNotEmpty()) contactBox.selectionModel.select(0)
        inviteLabel.text = ""
        busyLabel.visibleProperty().bind(busy)
        busyLabel.managedProperty().bind(busy)
    }

    /** Synchronous create; kept for tests. */
    fun create() {
        feedbackLabel.text = ""
        val contact = contactBox.selectionModel.selectedItem
        if (contact == null) {
            feedbackLabel.text = bundle.getString("room.invite.no_contact")
            return
        }
        val days = expiryField.text.trim().toLongOrNull()
        when (val result = viewModel.copyInvite(contact, nameField.text, days)) {
            is OpResult.Failure -> feedbackLabel.text = result.reason
            is OpResult.Success -> {
                createdInvite = result.value
                inviteLabel.text = result.value
                Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putString(result.value) })
            }
        }
    }

    /**
     * Creates the invite off the FX thread — re-publishing the room service can take a minute
     * on a slow network and must never freeze the dialog. [onDone] runs on the FX thread.
     */
    fun createAsync(onDone: () -> Unit) {
        if (busy.get()) return
        feedbackLabel.text = ""
        val contact = contactBox.selectionModel.selectedItem
        if (contact == null) {
            feedbackLabel.text = bundle.getString("room.invite.no_contact")
            return
        }
        val days = expiryField.text.trim().toLongOrNull()
        busy.set(true)
        executor.execute {
            val result = viewModel.copyInvite(contact, nameField.text, days)
            Platform.runLater {
                busy.set(false)
                when (result) {
                    is OpResult.Failure -> feedbackLabel.text = result.reason
                    is OpResult.Success -> {
                        createdInvite = result.value
                        inviteLabel.text = result.value
                        val clipboard = Clipboard.getSystemClipboard()
                        clipboard.setContent(ClipboardContent().apply { putString(result.value) })
                    }
                }
                onDone()
            }
        }
    }

    private fun contactCell(): ListCell<Contact> =
        object : ListCell<Contact>() {
            override fun updateItem(
                item: Contact?,
                empty: Boolean,
            ) {
                super.updateItem(item, empty)
                text = if (empty || item == null) null else item.alias
            }
        }
}
