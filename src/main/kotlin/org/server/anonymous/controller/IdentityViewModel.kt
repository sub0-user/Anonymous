package org.server.anonymous.controller

import javafx.beans.property.SimpleStringProperty

/**
 * Identity screen data. Phase 2 replaces the mock onion address with a real
 * keypair-generated onion service address and wires node status to TorNodeManager.
 */
class IdentityViewModel {
    val onionAddress =
        SimpleStringProperty("5t35w9m1a7k4p8n2x3v6b0q9r4s7t5u8w2y4a6c8d0f2g4h6j8k0m2n4p6r8t.onion")
    val nodeStatus = SimpleStringProperty("Node online — receiving messages")
    val dataDirectory = SimpleStringProperty(System.getProperty("user.home") + "/.anonymous")
    val versionLabel = SimpleStringProperty("v1.0-SNAPSHOT · phase 1 shell")
}
