package org.server.anonymous

import javafx.application.Application

object Launcher {
    @JvmStatic
    fun main(args: Array<String>) {
        Application.launch(AnonymousApplication::class.java)
    }
}
