package org.server.anonymous.controller

import javafx.beans.property.SimpleStringProperty

class SettingsViewModel {
    val nodeStatus = SimpleStringProperty("not connected — demo mode (real Tor in Phase 2)")
    val dataDirectory = SimpleStringProperty(System.getProperty("user.home") + "/.anonymous")
    val versionLabel = SimpleStringProperty("Anonymous v1.0-SNAPSHOT")
}
