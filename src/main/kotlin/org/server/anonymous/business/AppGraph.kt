package org.server.anonymous.business

import java.nio.file.Path

/**
 * Manual dependency root (no DI framework — PATTERNS.md §5).
 * Phase 3 wires the real P2P messaging stack; contacts/messages are in-memory until Phase 4.
 */
class AppGraph {
    val contactService: ContactService = ContactBook()
    val messageService =
        P2pMessageService(
            contactService,
            { torNodeManager.status() },
            { torNodeManager.inboundSocket },
            { identityService.getOrCreate() },
        )

    private val userData = Path.of(System.getProperty("user.home"), ".anonymous")
    val identityService = IdentityService(userData.resolve("identity"))
    val torNodeManager =
        TorNodeManager(
            identityService,
            TorProcessManager(userData.resolve("tor")),
            { ControlProtocolClient() },
        )

    fun start() {
        torNodeManager.addStatusListener { status ->
            if (status is NodeStatus.Online) messageService.startListener()
        }
        torNodeManager.start()
    }

    fun stop() {
        messageService.stop()
        torNodeManager.stop()
    }
}
