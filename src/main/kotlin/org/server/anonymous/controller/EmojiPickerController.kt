package org.server.anonymous.controller

import javafx.fxml.FXML
import javafx.geometry.Pos
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import java.util.ResourceBundle

/** Emoji strip: one row of the bundled palette; clicking one hands it to [onPick]. */
class EmojiPickerController(
    private val onPick: (String) -> Unit,
) {
    @FXML private lateinit var emojiList: ListView<String>

    private val bundle = ResourceBundle.getBundle("org.server.anonymous.emojis")

    @Suppress("UnusedPrivateMember") // invoked reflectively by FXML
    @FXML
    private fun initialize() {
        val emojis = bundle.getString("emoji.list").split(" ").filter { it.isNotBlank() }
        emojiList.items.setAll(emojis)
        emojiList.setCellFactory {
            object : ListCell<String>() {
                init {
                    styleClass.add("emoji-cell")
                    alignment = Pos.CENTER
                }

                override fun updateItem(
                    item: String?,
                    empty: Boolean,
                ) {
                    super.updateItem(item, empty)
                    text = if (empty || item == null) null else item
                }
            }
        }
        emojiList.selectionModel.selectedItemProperty().addListener { _, _, selected ->
            if (selected != null) {
                onPick(selected)
                emojiList.selectionModel.clearSelection()
            }
        }
    }
}
