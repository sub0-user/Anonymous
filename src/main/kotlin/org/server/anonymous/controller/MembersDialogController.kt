package org.server.anonymous.controller

import javafx.event.ActionEvent
import javafx.fxml.FXML
import javafx.fxml.FXMLLoader
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.scene.control.ListView
import javafx.scene.layout.Region
import javafx.util.Callback
import org.server.anonymous.AnonymousApplication
import org.server.anonymous.business.model.RoomMember
import java.util.ResourceBundle

/**
 * Members dialog (founder): lists the room's members with rename/remove actions.
 * Rename prompts for a new name; remove asks for confirmation, then kicks.
 */
class MembersDialogController(
    private val viewModel: RoomChatViewModel,
) {
    @FXML private lateinit var memberList: ListView<RoomMember>

    private val bundle = ResourceBundle.getBundle("org.server.anonymous.messages")

    @Suppress("UnusedPrivateMember") // invoked reflectively by FXML
    @FXML
    private fun initialize() {
        refresh()
    }

    fun refresh() {
        memberList.items.setAll(viewModel.members())
        memberList.setCellFactory {
            MemberRowCell(viewModel.isFounder, ::onRename, ::onRemove)
        }
    }

    private fun onRename(member: RoomMember) {
        val newName = askName(bundle.getString("room.member.rename") + ": " + member.name) ?: return
        if (viewModel.renameMember(member, newName)) refresh()
    }

    private fun onRemove(member: RoomMember) {
        val confirm =
            javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.CONFIRMATION,
                bundle.getString("room.member.remove.confirm") + " " + member.name + "?",
            )
        if (confirm.showAndWait().filter { it == ButtonType.OK }.isPresent) {
            if (viewModel.removeMember(member)) refresh()
        }
    }

    private fun askName(hint: String): String? {
        val dialog = Dialog<String>()
        val loader = FXMLLoader(MembersDialogController::class.java.getResource("name-dialog.fxml"), bundle)
        loader.controllerFactory = Callback { NameDialogController(hint) }
        dialog.dialogPane = loader.load()
        dialog.dialogPane.stylesheets.add(AnonymousApplication.stylesheet())
        dialog.dialogPane.minHeight = Region.USE_COMPUTED_SIZE
        dialog.title = bundle.getString("room.name")
        val okType = ButtonType(bundle.getString("dialog.ok"), ButtonBar.ButtonData.OK_DONE)
        dialog.dialogPane.buttonTypes.add(okType)
        val controller = loader.getController<NameDialogController>()
        dialog.dialogPane.lookupButton(okType).addEventFilter(ActionEvent.ACTION) { event ->
            if (controller.name.isEmpty()) event.consume()
        }
        dialog.setResultConverter { controller.name }
        return dialog.showAndWait().orElse(null)
    }
}
