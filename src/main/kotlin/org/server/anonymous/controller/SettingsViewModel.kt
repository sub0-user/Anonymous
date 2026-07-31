package org.server.anonymous.controller

import javafx.beans.property.SimpleStringProperty

class SettingsViewModel {
    val nodeStatus = SimpleStringProperty("online (Tor integration lands in Phase 2)")
    val dataDirectory = SimpleStringProperty(System.getProperty("user.home") + "/.anonymous")
    val versionLabel = SimpleStringProperty("Anonymous v1.0-SNAPSHOT")
}
