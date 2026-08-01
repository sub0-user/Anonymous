package org.server.anonymous

import javafx.application.Application
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.stage.Stage
import javafx.util.Callback
import org.server.anonymous.business.AppGraph
import org.server.anonymous.controller.MainController
import java.util.ResourceBundle

class AnonymousApplication : Application() {
    private val appGraph = AppGraph()

    override fun start(stage: Stage) {
        val bundle = ResourceBundle.getBundle("org.server.anonymous.messages")
        val loader =
            FXMLLoader(
                AnonymousApplication::class.java.getResource("main-view.fxml"),
                bundle,
            )
        loader.controllerFactory =
            Callback { type ->
                when (type) {
                    MainController::class.java -> MainController(appGraph)
                    else -> type.getDeclaredConstructor().newInstance()
                }
            }
        val root = loader.load<Parent>()
        val scene = Scene(root)
        scene.stylesheets.add(stylesheet())
        stage.title = bundle.getString("app.name")
        stage.icons.add(
            javafx.scene.image.Image(
                AnonymousApplication::class.java.getResourceAsStream("logo/icon-square.png"),
            ),
        )
        stage.minWidth = 900.0
        stage.minHeight = 600.0
        stage.scene = scene
        stage.show()
        appGraph.start()
        Runtime.getRuntime().addShutdownHook(Thread { appGraph.stop() })
    }

    companion object {
        fun stylesheet(): String = AnonymousApplication::class.java.getResource("css/onion-dark.css").toExternalForm()
    }
}
