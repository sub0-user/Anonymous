package org.server.anonymous.controller

import javafx.application.Platform
import javafx.beans.property.SimpleStringProperty
import org.server.anonymous.business.NodeStatus
import org.server.anonymous.business.NodeStatusSource

class SettingsViewModel(
    private val nodeStatusSource: NodeStatusSource,
) {
    val nodeStatus = SimpleStringProperty("starting Tor…")
    val dataDirectory = SimpleStringProperty(System.getProperty("user.home") + "/.anonymous")
    val versionLabel = SimpleStringProperty("Anonymous v1.0-SNAPSHOT")

    init {
        nodeStatusSource.addStatusListener { status -> Platform.runLater { applyStatus(status) } }
    }

    fun applyStatus(status: NodeStatus) {
        nodeStatus.set(
            when (status) {
                is NodeStatus.Offline -> "node offline: ${status.reason}"
                is NodeStatus.Bootstrapping -> "bootstrapping ${status.progress}%…"
                is NodeStatus.Online -> "Node online — receiving messages"
            },
        )
    }
}
