package org.server.anonymous.controller

import javafx.application.Platform
import javafx.beans.property.SimpleBooleanProperty
import javafx.fxml.FXML
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.TextField
import org.server.anonymous.business.OpResult
import org.server.anonymous.business.model.Contact
import java.util.ResourceBundle
import java.util.concurrent.Executors

/**
 * Add-member dialog (founder): pick a contact — a contact is already trusted, so adding
 * needs no consent — choose their room name, and the invite is sent to them as a 1:1 chat
 * message they accept from there. There is no invite string to copy and nothing is
 * published: creating the invite is local, so the only failure is the contact being
 * offline when we need to exchange keys.
 */
class AddMemberDialogController(
    private val viewModel: RoomChatViewModel,
) {
    @FXML private lateinit var contactBox: ComboBox<Contact>

    @FXML private lateinit var nameField: TextField

    @FXML private lateinit var feedbackLabel: Label

    @FXML private lateinit var busyLabel: Label

    private val bundle = ResourceBundle.getBundle("org.server.anonymous.messages")

    /** Set when the invite was created; the dialog closes only then. */
    var createdInvite: String? = null

    /** How the created invite was delivered; read after the dialog closes. */
    var outcome: InviteOutcome? = null

    /** True while the key exchange for a never-chatted contact runs. */
    val busy = SimpleBooleanProperty(false)

    private val executor =
        Executors.newSingleThreadExecutor { r -> Thread(r, "add-member").apply { isDaemon = true } }

    @Suppress("UnusedPrivateMember") // invoked reflectively by FXML
    @FXML
    private fun initialize() {
        val contacts = viewModel.contactsForInvite()
        contactBox.items.setAll(contacts)
        contactBox.setCellFactory { contactCell() }
        contactBox.buttonCell = contactCell()
        contactBox.selectionModel.selectedItemProperty().addListener { _, _, selected ->
            if (selected != null && nameField.text.isBlank()) {
                nameField.text = selected.alias
            }
        }
        if (contacts.isNotEmpty()) contactBox.selectionModel.select(0)
        busyLabel.visibleProperty().bind(busy)
        busyLabel.managedProperty().bind(busy)
        if (contacts.isEmpty()) {
            // Explain the empty picker before the user even clicks Add.
            feedbackLabel.text = bundle.getString("room.add.no_contact")
        }
    }

    /** Synchronous create; kept for tests. */
    fun create() {
        feedbackLabel.text = ""
        val contact = contactBox.selectionModel.selectedItem
        if (contact == null) {
            feedbackLabel.text = bundle.getString("room.add.no_contact")
            return
        }
        when (val result = viewModel.addMember(contact, nameField.text, null)) {
            is OpResult.Failure -> feedbackLabel.text = result.reason
            is OpResult.Success -> completeInvite(result.value, contact)
        }
    }

    /**
     * Creates the invite off the FX thread (the key exchange for a never-chatted contact is a
     * session handshake and must never freeze the dialog). [onDone] runs on the FX thread.
     */
    fun createAsync(onDone: () -> Unit) {
        if (busy.get()) return
        feedbackLabel.text = ""
        val contact = contactBox.selectionModel.selectedItem
        if (contact == null) {
            feedbackLabel.text = bundle.getString("room.add.no_contact")
            return
        }
        busy.set(true)
        executor.execute {
            val result = viewModel.addMember(contact, nameField.text, null)
            Platform.runLater {
                busy.set(false)
                when (result) {
                    is OpResult.Failure -> feedbackLabel.text = result.reason
                    is OpResult.Success -> completeInvite(result.value, contact)
                }
                onDone()
            }
        }
    }

    /** Invite created: deliver it as a chat message to the contact — no clipboard involved. */
    private fun completeInvite(
        invite: String,
        contact: Contact,
    ) {
        createdInvite = invite
        val sent = viewModel.sendInvite(contact, invite)
        outcome =
            InviteOutcome(
                invite = invite,
                contactAlias = contact.alias,
                delivered = sent is OpResult.Success,
                sendError = (sent as? OpResult.Failure)?.reason,
            )
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
