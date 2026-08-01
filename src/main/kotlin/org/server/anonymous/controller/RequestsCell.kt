package org.server.anonymous.controller

import javafx.fxml.FXML
import javafx.fxml.FXMLLoader
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.layout.HBox
import org.server.anonymous.business.model.ContactRequest

/** Renders one pending request row from requests-row.fxml. */
class RequestsCell : ListCell<ContactRequest>() {
    @FXML private lateinit var addressLabel: Label

    @FXML private lateinit var previewLabel: Label

    private val rootNode: HBox

    init {
        val loader = FXMLLoader(RequestsCell::class.java.getResource("requests-row.fxml"))
        loader.setController(this)
        rootNode = loader.load<HBox>()
    }

    override fun updateItem(
        item: ContactRequest?,
        empty: Boolean,
    ) {
        super.updateItem(item, empty)
        if (item == null || empty) {
            graphic = null
            return
        }
        addressLabel.text = item.address.value
        previewLabel.text = item.preview
        graphic = rootNode
    }
}
