package org.server.anonymous.controller

import javafx.fxml.FXML
import javafx.geometry.Pos
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.image.ImageView

/** Emoji strip: one row of the bundled color palette; clicking one hands it to [onPick]. */
class EmojiPickerController(
    private val onPick: (String) -> Unit,
) {
    @FXML private lateinit var emojiList: ListView<String>

    @Suppress("UnusedPrivateMember") // invoked reflectively by FXML
    @FXML
    private fun initialize() {
        emojiList.items.setAll(EmojiImages.emojis)
        emojiList.setCellFactory {
            object : ListCell<String>() {
                private val view = ImageView()

                init {
                    styleClass.add("emoji-cell")
                    alignment = Pos.CENTER
                    graphic = view
                    view.fitHeight = 26.0
                    view.fitWidth = 26.0
                    view.setPreserveRatio(true)
                }

                override fun updateItem(
                    item: String?,
                    empty: Boolean,
                ) {
                    super.updateItem(item, empty)
                    view.image = if (empty || item == null) null else EmojiImages.imageOf(item)
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
