package org.server.anonymous

import javafx.application.Application
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.stage.Stage
import javafx.util.Callback
import org.server.anonymous.business.AppGraph
import org.server.anonymous.controller.MainController

class AnonymousApplication : Application() {
    private val appGraph = AppGraph()

    override fun start(stage: Stage) {
        val loader = FXMLLoader(AnonymousApplication::class.java.getResource("main-view.fxml"))
        loader.controllerFactory =
            Callback { type ->
                when (type) {
                    MainController::class.java -> MainController(appGraph)
                    else -> type.getDeclaredConstructor().newInstance()
                }
            }
        val root = loader.load<Parent>()
        stage.title = "Anonymous"
        stage.minWidth = 900.0
        stage.minHeight = 600.0
        stage.scene = Scene(root)
        stage.show()
    }
}
