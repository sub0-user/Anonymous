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
import org.server.anonymous.business.OnionAddress
import org.server.anonymous.business.model.Contact
import org.server.anonymous.controller.AddContactDialogController
import org.server.anonymous.controller.AddContactViewModel
import org.server.anonymous.controller.ChatController
import org.server.anonymous.controller.ChatViewModel
import org.server.anonymous.controller.FakeNodeStatusSource
import org.server.anonymous.controller.IdentityController
import org.server.anonymous.controller.IdentityViewModel
import org.server.anonymous.controller.MainController
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
            loader.controllerFactory =
                Callback { IdentityController(IdentityViewModel(FakeNodeStatusSource(), tempIdentity())) }
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
}
