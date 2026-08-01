package org.server.anonymous.business

import java.nio.file.Path

/**
 * Manual dependency root (no DI framework — PATTERNS.md §5).
 * Phase 4 wires rooms: the founder host (RoomHost) and the member side (RoomMessenger)
 * share the room store, the outbound Tor sender, and the connected Tor control client.
 */
class AppGraph {
    val contactService: ContactService = ContactBook()

    private val userData = Path.of(System.getProperty("user.home"), ".anonymous")
    val identityService = IdentityService(userData.resolve("identity"))
    val torNodeManager =
        TorNodeManager(
            identityService,
            TorProcessManager(userData.resolve("tor")),
            { ControlProtocolClient() },
        )

    private val connectedControl: () -> TorControl = {
        torNodeManager.controlClient ?: error("node offline")
    }

    private val roomStore = RoomStore(userData.resolve("rooms"))

    private val torSender: TorSender by lazy {
        TorSender({ torNodeManager.status() }, { identityKeys() })
    }

    private val clientAuth: OnionClientAuth by lazy {
        OnionClientAuth({ torNodeManager.clientAuthDir() }, connectedControl)
    }

    val roomMessenger: RoomMessenger by lazy {
        RoomMessenger(roomStore, { identityService.getOrCreate() }, { address, key, type, body ->
            torSender.send(address, key, type, body)
        }, clientAuth)
    }

    val roomHost: RoomHost by lazy {
        RoomHost(
            roomStore,
            { torNodeManager.status() },
            connectedControl,
            { identityService.getOrCreate() },
        ) { address, key, type, body ->
            torSender.send(address, key, type, body)
        }
    }

    val messageService =
        P2pMessageService(
            contactService,
            { torNodeManager.status() },
            { torNodeManager.inboundSocket },
            { identityService.getOrCreate() },
            roomInbound = { key, address, type, body -> roomMessenger.handleInbound(key, address, type, body) },
        )

    private fun identityKeys(): X25519KeyPair = IdentityKeys.x25519KeyPairFromSeed(identityService.getOrCreate().seed)

    fun start() {
        torNodeManager.addStatusListener { status ->
            if (status is NodeStatus.Online) {
                messageService.startListener()
                roomHost.start()
            }
        }
        torNodeManager.start()
    }

    fun stop() {
        messageService.stop()
        roomHost.stop()
        torNodeManager.stop()
    }
}
