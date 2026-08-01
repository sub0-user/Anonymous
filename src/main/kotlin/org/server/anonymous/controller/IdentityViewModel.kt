package org.server.anonymous.controller

import javafx.application.Platform
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.property.SimpleStringProperty
import org.server.anonymous.business.ContactService
import org.server.anonymous.business.DataBackup
import org.server.anonymous.business.IdentityService
import org.server.anonymous.business.NodeStatus
import org.server.anonymous.business.NodeStatusSource
import org.server.anonymous.business.OpResult
import org.server.anonymous.business.RoomStore

/**
 * Identity screen data. The onion address and node status come from the real
 * Tor node; they are honest — "online" only when NodeStatus.Online.
 */
class IdentityViewModel(
    private val nodeStatusSource: NodeStatusSource,
    private val identityService: IdentityService,
    private val contactService: ContactService,
    private val roomStore: RoomStore,
) {
    val onionAddress = SimpleStringProperty("starting…")
    val nodeStatus = SimpleStringProperty("starting Tor…")
    val dataDirectory = SimpleStringProperty(System.getProperty("user.home") + "/.anonymous")
    val versionLabel = SimpleStringProperty("v1.0-SNAPSHOT · phase 3")
    val backupMessage = SimpleObjectProperty<String?>(null)

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

    /** Returns the passphrase-encrypted backup bytes, or a failure. */
    fun exportIdentity(passphrase: CharArray): OpResult<ByteArray> =
        runCatching {
            DataBackup.export(
                seed = identityService.getOrCreate().seed,
                contacts = contactService.listContacts(),
                blocked = contactService.blockedAddresses(),
                rooms = roomStore.loadAll(),
                passphrase = passphrase,
            )
        }.fold(
            { OpResult.Success(it) },
            { OpResult.Failure(it.message ?: "Export failed") },
        )

    /** Restores the seed, contacts and rooms after validating the passphrase. */
    fun importIdentity(
        data: ByteArray,
        passphrase: CharArray,
    ): OpResult<String> =
        runCatching {
            val contents = DataBackup.import(data, passphrase)
            identityService.replace(contents.seed)
            contactService.restore(contents.contacts, contents.blocked)
            roomStore.restoreAll(contents.rooms)
            "ok"
        }.fold(
            { OpResult.Success(it) },
            { OpResult.Failure(it.message ?: "Import failed") },
        )
}
