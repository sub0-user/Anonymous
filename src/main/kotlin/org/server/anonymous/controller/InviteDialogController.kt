package org.server.anonymous.controller

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

/** Invite dialog (founder): pick a contact, their room name, optional expiry, create + copy. */
class InviteDialogController(
    private val viewModel: RoomChatViewModel,
) {
    @FXML private lateinit var contactBox: ComboBox<Contact>

    @FXML private lateinit var nameField: TextField

    @FXML private lateinit var expiryField: TextField

    @FXML private lateinit var inviteLabel: Label

    @FXML private lateinit var feedbackLabel: Label

    private val bundle = ResourceBundle.getBundle("org.server.anonymous.messages")

    /** Set when the invite was created; the dialog closes only then. */
    var createdInvite: String? = null

    @Suppress("UnusedPrivateMember") // invoked reflectively by FXML
    @FXML
    private fun initialize() {
        val contacts = viewModel.contactsForInvite()
        contactBox.items.setAll(contacts)
        contactBox.setCellFactory { contactCell() }
        contactBox.buttonCell = contactCell()
        if (contacts.isNotEmpty()) contactBox.selectionModel.select(0)
        inviteLabel.text = ""
    }

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
