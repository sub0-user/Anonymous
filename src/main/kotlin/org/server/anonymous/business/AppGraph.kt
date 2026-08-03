package org.server.anonymous.business

import org.server.anonymous.business.model.MessageItem
import org.server.anonymous.business.model.RoomMessageItem
import java.nio.file.Path

/**
 * Manual dependency root (no DI framework — PATTERNS.md §5).
 * Phase 4 wires rooms: the founder host (RoomHost) and the member side (RoomMessenger)
 * share the room store, the outbound Tor sender, and the connected Tor control client.
 */
class AppGraph {
    /** Contacts persist to one 0600 properties file (Phase A1 pattern). */
    val contactService: ContactService by lazy { ContactBook(userData.resolve("contacts.properties")) }

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

    val roomStore = RoomStore(userData.resolve("rooms"))

    /** Encrypted at-rest 1:1 history — one file, records carry the contact id (Phase A1). */
    private val messageHistory: MessageJournal<MessageItem> by lazy {
        MessageJournal(
            userData.resolve("messages").resolve("conversations.hist"),
            { identityService.getOrCreate() },
            HistoryCodec::encodeMessageItem,
            HistoryCodec::decodeMessageItem,
        )
    }

    /** Encrypted at-rest room history — one file, records carry the room id (Phase A1). */
    private val roomHistory: MessageJournal<RoomMessageItem> by lazy {
        MessageJournal(
            userData.resolve("rooms").resolve("messages.hist"),
            { identityService.getOrCreate() },
            HistoryCodec::encodeRoomItem,
            HistoryCodec::decodeRoomItem,
        )
    }

    private val torSender: TorSender by lazy {
        TorSender({ torNodeManager.status() }, { identityKeys() })
    }

    val roomMessenger: RoomMessenger by lazy {
        RoomMessenger(
            roomStore,
            { identityService.getOrCreate() },
            { address, key, type, body ->
                torSender.send(address, key, type, body)
            },
            roomHistory = roomHistory,
        )
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
            messageHistory = messageHistory,
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
