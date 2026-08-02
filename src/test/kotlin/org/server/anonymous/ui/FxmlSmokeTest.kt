package org.server.anonymous.ui

import javafx.fxml.FXMLLoader
import javafx.scene.Node
import javafx.scene.control.DialogPane
import javafx.util.Callback
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.server.anonymous.AnonymousApplication
import org.server.anonymous.business.AppGraph
import org.server.anonymous.business.IdentityService
import org.server.anonymous.business.InMemoryContactService
import org.server.anonymous.business.InMemoryMessageService
import org.server.anonymous.business.NodeStatus
import org.server.anonymous.business.OnionAddress
import org.server.anonymous.business.RoomHost
import org.server.anonymous.business.RoomMessenger
import org.server.anonymous.business.RoomStore
import org.server.anonymous.business.TorControl
import org.server.anonymous.business.model.Contact
import org.server.anonymous.controller.AddContactDialogController
import org.server.anonymous.controller.AddContactViewModel
import org.server.anonymous.controller.ChatController
import org.server.anonymous.controller.ChatViewModel
import org.server.anonymous.controller.FakeNodeStatusSource
import org.server.anonymous.controller.IdentityController
import org.server.anonymous.controller.IdentityViewModel
import org.server.anonymous.controller.InviteDialogController
import org.server.anonymous.controller.JoinRoomDialogController
import org.server.anonymous.controller.JoinRoomViewModel
import org.server.anonymous.controller.MainController
import org.server.anonymous.controller.MembersDialogController
import org.server.anonymous.controller.NameDialogController
import org.server.anonymous.controller.NewRoomDialogController
import org.server.anonymous.controller.NewRoomViewModel
import org.server.anonymous.controller.PassphraseDialogController
import org.server.anonymous.controller.RoomChatController
import org.server.anonymous.controller.RoomChatViewModel
import org.server.anonymous.controller.SettingsController
import org.server.anonymous.controller.SettingsViewModel
import java.nio.file.Files
import java.nio.file.Path
import java.util.ResourceBundle

class FxmlSmokeTest {
    private val bundle = ResourceBundle.getBundle("org.server.anonymous.messages")

    private fun tempIdentity(): IdentityService {
        val dir: Path = Files.createTempDirectory("anonymous-fxml-smoke").also { it.toFile().deleteOnExit() }
        return IdentityService(dir)
    }

    @Test
    fun `main view loads with full wiring`() =
        JavaFxTestSupport.onFxThread {
            val appGraph = AppGraph()
            val loader = FXMLLoader(AnonymousApplication::class.java.getResource("main-view.fxml"), bundle)
            loader.controllerFactory =
                Callback { type ->
                    when (type) {
                        MainController::class.java -> MainController(appGraph)
                        else -> type.getDeclaredConstructor().newInstance()
                    }
                }
            assertNotNull(loader.load<Node>())
        }

    @Test
    fun `identity view loads`() =
        JavaFxTestSupport.onFxThread {
            val loader = FXMLLoader(MainController::class.java.getResource("identity-view.fxml"), bundle)
            val identity =
                IdentityViewModel(
                    FakeNodeStatusSource(),
                    tempIdentity(),
                    InMemoryContactService(),
                    RoomStore(tempDir()),
                )
            loader.controllerFactory = Callback { IdentityController(identity) }
            assertNotNull(loader.load<Node>())
        }

    @Test
    fun `chat view loads`() =
        JavaFxTestSupport.onFxThread {
            val contact = Contact(1, "raven", OnionAddress("z".repeat(56) + ".onion"), "2m ago")
            val loader = FXMLLoader(MainController::class.java.getResource("chat-view.fxml"), bundle)
            loader.controllerFactory =
                Callback {
                    ChatController(
                        ChatViewModel(
                            InMemoryMessageService(),
                            InMemoryContactService(),
                            FakeNodeStatusSource(),
                            tempIdentity(),
                            contact,
                        ),
                    )
                }
            assertNotNull(loader.load<Node>())
        }

    @Test
    fun `add contact dialog loads`() =
        JavaFxTestSupport.onFxThread {
            val loader = FXMLLoader(MainController::class.java.getResource("add-contact-dialog.fxml"), bundle)
            loader.controllerFactory =
                Callback { AddContactDialogController(AddContactViewModel(InMemoryContactService())) }
            assertNotNull(loader.load<DialogPane>())
        }

    @Test
    fun `settings view loads`() =
        JavaFxTestSupport.onFxThread {
            val loader = FXMLLoader(MainController::class.java.getResource("settings-view.fxml"), bundle)
            loader.controllerFactory = Callback { SettingsController(SettingsViewModel(FakeNodeStatusSource())) }
            assertNotNull(loader.load<Node>())
        }

    @Test
    fun `passphrase dialog root is a DialogPane`() =
        JavaFxTestSupport.onFxThread {
            // IdentityController assigns the loaded root to dialog.dialogPane, so a layout
            // box (VBox) root would throw ClassCastException at runtime on the Backup click.
            val loader = FXMLLoader(IdentityController::class.java.getResource("passphrase-dialog.fxml"), bundle)
            loader.controllerFactory =
                Callback { PassphraseDialogController("hint") }
            assertNotNull(loader.load<DialogPane>())
        }

    // --- Phase 4 rooms ---

    private class FakeTorControl : TorControl {
        override fun connect(
            host: String,
            port: Int,
        ) = Unit

        override fun authenticate(cookie: ByteArray) = Unit

        override fun bootstrapProgress(): Int? = 100

        override fun addOnionService(
            seed: ByteArray,
            virtualPort: Int,
            targetHost: String,
            targetPort: Int,
        ): String = "a".repeat(56) + ".onion"

        override fun addOnionServiceWithClientAuth(
            seed: ByteArray,
            virtualPort: Int,
            targetHost: String,
            targetPort: Int,
            clientAuthBlobs: List<String>,
        ): String = "a".repeat(56) + ".onion"

        override fun deleteOnionService(address: String) = Unit

        override fun signalHup() = Unit

        override fun close() = Unit
    }

    private fun tempDir(): Path = Files.createTempDirectory("anonymous-fxml-room").also { it.toFile().deleteOnExit() }

    private fun roomHost(): org.server.anonymous.business.RoomHost {
        val identity = IdentityService(tempDir()).getOrCreate()
        return RoomHost(
            RoomStore(tempDir()),
            { NodeStatus.Online("a".repeat(56) + ".onion", 40_000) },
            { FakeTorControl() },
            { identity },
        ) { _, _, _, _ -> true }
    }

    private fun roomMessenger(): RoomMessenger =
        RoomMessenger(
            RoomStore(tempDir()),
            { IdentityService(tempDir()).getOrCreate() },
            sender = { _, _, _, _ -> true },
        )

    private fun roomChatViewModel(): RoomChatViewModel {
        val messenger = roomMessenger()
        return RoomChatViewModel(messenger, null, 0L, contacts = { emptyList() })
    }

    @Test
    fun `room chat view loads`() =
        JavaFxTestSupport.onFxThread {
            val loader = FXMLLoader(MainController::class.java.getResource("room-chat-view.fxml"), bundle)
            loader.controllerFactory = Callback { RoomChatController(roomChatViewModel()) }
            assertNotNull(loader.load<Node>())
        }

    @Test
    fun `new room dialog loads`() =
        JavaFxTestSupport.onFxThread {
            val loader = FXMLLoader(MainController::class.java.getResource("new-room-dialog.fxml"), bundle)
            loader.controllerFactory = Callback { NewRoomDialogController(NewRoomViewModel(roomHost())) }
            assertNotNull(loader.load<DialogPane>())
        }

    @Test
    fun `join room dialog loads`() =
        JavaFxTestSupport.onFxThread {
            val loader = FXMLLoader(MainController::class.java.getResource("join-room-dialog.fxml"), bundle)
            loader.controllerFactory = Callback { JoinRoomDialogController(JoinRoomViewModel(roomMessenger())) }
            assertNotNull(loader.load<DialogPane>())
        }

    @Test
    fun `members and invite dialogs load`() =
        JavaFxTestSupport.onFxThread {
            val viewModel = roomChatViewModel()
            val members = FXMLLoader(MainController::class.java.getResource("members-dialog.fxml"), bundle)
            members.controllerFactory = Callback { MembersDialogController(viewModel) }
            assertNotNull(members.load<DialogPane>())
            val invite = FXMLLoader(MainController::class.java.getResource("invite-dialog.fxml"), bundle)
            invite.controllerFactory = Callback { InviteDialogController(viewModel) }
            assertNotNull(invite.load<DialogPane>())
            val name = FXMLLoader(MainController::class.java.getResource("name-dialog.fxml"), bundle)
            name.controllerFactory = Callback { NameDialogController("hint") }
            assertNotNull(name.load<DialogPane>())
        }
}
