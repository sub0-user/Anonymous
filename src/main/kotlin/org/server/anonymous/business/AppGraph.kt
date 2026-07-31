package org.server.anonymous.business

import java.nio.file.Path

/**
 * Manual dependency root (no DI framework — PATTERNS.md §5).
 * Phase 4 replaces the in-memory impls with real repositories; this class is the only place that changes.
 */
class AppGraph {
    val contactService: ContactService = InMemoryContactService()
    val messageService: MessageService = InMemoryMessageService()

    private val userData = Path.of(System.getProperty("user.home"), ".anonymous")
    val identityService = IdentityService(userData.resolve("identity"))
    val torNodeManager =
        TorNodeManager(
            identityService,
            TorProcessManager(userData.resolve("tor")),
            { ControlProtocolClient() },
        )

    fun start() {
        torNodeManager.start()
    }

    fun stop() {
        torNodeManager.stop()
    }
}
