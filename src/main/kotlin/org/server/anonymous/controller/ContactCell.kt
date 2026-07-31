package org.server.anonymous.controller

import javafx.fxml.FXML
import javafx.fxml.FXMLLoader
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.layout.VBox
import org.server.anonymous.business.model.Contact

/** Renders one sidebar contact row from contact-row.fxml. */
class ContactCell : ListCell<Contact>() {
    @FXML private lateinit var aliasLabel: Label

    @FXML private lateinit var addressLabel: Label

    private val rootNode: VBox

    init {
        val loader = FXMLLoader(ContactCell::class.java.getResource("contact-row.fxml"))
        loader.setController(this)
        rootNode = loader.load<VBox>()
    }

    override fun updateItem(
        item: Contact?,
        empty: Boolean,
    ) {
        super.updateItem(item, empty)
        if (item == null || empty) {
            graphic = null
            return
        }
        aliasLabel.text = item.alias
        addressLabel.text = item.address.value.take(12) + "…"
        graphic = rootNode
    }
}
