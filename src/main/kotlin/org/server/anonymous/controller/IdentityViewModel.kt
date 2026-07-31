package org.server.anonymous.controller

import javafx.application.Platform
import javafx.beans.property.SimpleStringProperty
import org.server.anonymous.business.NodeStatus
import org.server.anonymous.business.NodeStatusSource

/**
 * Identity screen data. The onion address and node status come from the real
 * Tor node; they are honest — "online" only when NodeStatus.Online.
 */
class IdentityViewModel(
    private val nodeStatusSource: NodeStatusSource,
) {
    val onionAddress = SimpleStringProperty("starting…")
    val nodeStatus = SimpleStringProperty("starting Tor…")
    val dataDirectory = SimpleStringProperty(System.getProperty("user.home") + "/.anonymous")
    val versionLabel = SimpleStringProperty("v1.0-SNAPSHOT · phase 2")

    init {
        nodeStatusSource.addStatusListener { status -> Platform.runLater { applyStatus(status) } }
    }

    /** Applies a node status to the properties (called on the FX thread). */
    fun applyStatus(status: NodeStatus) {
        when (status) {
            is NodeStatus.Offline -> {
                onionAddress.set("—")
                nodeStatus.set("node offline: ${status.reason}")
            }
            is NodeStatus.Bootstrapping -> {
                nodeStatus.set("bootstrapping ${status.progress}%…")
            }
            is NodeStatus.Online -> {
                onionAddress.set(status.address)
                nodeStatus.set("Node online — receiving messages")
            }
        }
    }
}
