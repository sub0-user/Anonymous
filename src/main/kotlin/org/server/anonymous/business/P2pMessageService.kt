package org.server.anonymous.business

import org.server.anonymous.business.model.Contact
import org.server.anonymous.business.model.MessageDirection
import org.server.anonymous.business.model.MessageItem
import org.server.anonymous.business.model.MessageStatus
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Real Phase 3 messaging: sends text over Tor (via our SOCKS port) to a contact's onion
 * service and receives inbound messages on the node's reserved listener. In-memory store;
 * contacts, requests, blocks and peer-key binding come from [ContactService]. Persistence
 * is Phase 4.
 *
 * @Suppress TooManyFunctions: a transport service is naturally many small cohesive steps;
 * splitting them across classes would hide the flow.
 */
@Suppress("TooManyFunctions")
class P2pMessageService(
    private val contactService: ContactService,
    private val nodeStatus: () -> NodeStatus,
    private val inboundSocket: () -> ServerSocket?,
    private val identity: () -> Identity,
    private val socketFactory: (Int, String, Int) -> Socket = defaultSocksSocket,
    private val rateLimitPerMinute: Int = 30,
) : MessageService {
    private val store = mutableMapOf<Long, MutableList<MessageItem>>()
    private val listeners = CopyOnWriteArrayList<(MessageItem) -> Unit>()
    private val senderExecutor =
        Executors.newSingleThreadExecutor { r -> Thread(r, "msg-send").apply { isDaemon = true } }
    private val listenerExecutor =
        Executors.newSingleThreadExecutor { r -> Thread(r, "msg-listen").apply { isDaemon = true } }
    private val rateLimit = RateLimiter(maxPerMinute = rateLimitPerMinute)
    private var listenerStarted = false
    private var nextId = 1L

    private val keys: X25519KeyPair by lazy { IdentityKeys.x25519KeyPairFromSeed(identity().seed) }
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

    override fun messagesFor(contactId: Long): List<MessageItem> =
        synchronized(store) {
            store[contactId]?.toList() ?: emptyList()
        }

    override fun addMessageListener(listener: (MessageItem) -> Unit) {
        listeners += listener
    }

    override fun send(
        contactId: Long,
        body: String,
    ): OpResult<MessageItem> {
        val trimmed = body.trim()
        val contact = contactService.listContacts().firstOrNull { it.id == contactId }
        val failure =
            when {
                trimmed.isEmpty() -> "Message is empty"
                contact == null -> "Contact not found"
                contactService.isBlocked(contact.address.value) -> "Contact is blocked"
                else -> null
            }
        if (failure != null) return OpResult.Failure(failure)
        val message = MessageItem(nextId(), MessageDirection.OUT, trimmed, MessageStatus.SENT, nowLabel())
        synchronized(store) { store.getOrPut(contactId) { mutableListOf() } += message }
        notify(message)
        deliverAsync(contact!!, message)
        return OpResult.Success(message)
    }

    /** Starts the inbound accept loop; idempotent. Binds to the node's current listener socket. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // accept can fail during a restart
    fun startListener() {
        if (listenerStarted) return
        listenerStarted = true
        listenerExecutor.execute {
            var bound: ServerSocket? = null
            while (!Thread.currentThread().isInterrupted) {
                val socket = inboundSocket()
                if (socket == null || socket.isClosed) {
                    Thread.sleep(1000)
                    continue
                }
                if (socket != bound) bound = socket
                try {
                    handleConnection(socket.accept())
                } catch (t: Throwable) {
                    Thread.sleep(1000) // accept failed during a node restart — retry
                }
            }
        }
    }

    override fun stop() {
        senderExecutor.shutdownNow()
        listenerExecutor.shutdownNow()
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // any failure maps to FAILED
    private fun deliverAsync(
        contact: Contact,
        message: MessageItem,
    ) {
        senderExecutor.execute {
            val status =
                try {
                    val online = nodeStatus() as? NodeStatus.Online ?: error("node offline")
                    val socket = socketFactory(online.socksPort, contact.address.value, 80)
                    try {
                        val session = MessageSession.initiate(socket, keys, online.address)
                        try {
                            val known = contactService.peerPublicKeyOf(contact.id)
                            if (known != null && !known.contentEquals(session.peerPublicKey)) {
                                error("peer key changed — verify the safety number")
                            }
                            contactService.bindPeerKey(contact.id, session.peerPublicKey)
                            session.sendMessage(WireProtocol.CONTENT_TEXT.toByte(), message.body.toByteArray())
                            MessageStatus.DELIVERED
                        } finally {
                            session.close()
                        }
                    } finally {
                        socket.close()
                    }
                } catch (t: Throwable) {
                    MessageStatus.FAILED
                }
            updateStatus(contact.id, message.id, status)
        }
    }

    // Any single connection must never kill the listener; malformed input is dropped.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun handleConnection(socket: Socket) {
        try {
            val session = openSession(socket) ?: return
            try {
                if (isAcceptable(session)) {
                    receiveAndStore(session)
                }
            } finally {
                session.close()
            }
        } catch (t: Throwable) {
            // Malformed or malicious connection — drop silently and keep listening.
        } finally {
            runCatching { socket.close() }
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // a failed handshake just drops it
    private fun openSession(socket: Socket): MessageSession? {
        val online = nodeStatus() as? NodeStatus.Online ?: return null
        return try {
            MessageSession.respond(socket, keys, online.address)
        } catch (t: Throwable) {
            null
        }
    }

    /** A connection is processed only when the peer is not blocked and not rate-limited. */
    private fun isAcceptable(session: MessageSession): Boolean {
        if (contactService.isBlocked(session.peerAddress)) return false
        return rateLimit.allow(session.peerAddress)
    }

    private fun receiveAndStore(session: MessageSession) {
        val received = session.receiveMessage()
        val text = received.body.toString(Charsets.UTF_8)
        val contact = contactService.findByAddress(session.peerAddress)
        when {
            received.contentType.toInt() != WireProtocol.CONTENT_TEXT -> return
            contact == null -> contactService.addRequest(session.peerAddress, text.take(64))
            keyBindingOk(contact, session.peerPublicKey) -> storeInbound(contact, text)
            else -> Unit
        }
    }

    private fun storeInbound(
        contact: Contact,
        text: String,
    ) {
        val message = MessageItem(nextId(), MessageDirection.IN, text, MessageStatus.DELIVERED, nowLabel())
        synchronized(store) { store.getOrPut(contact.id) { mutableListOf() } += message }
        notify(message)
    }

    /** First contact binds the key; later contacts must present the same key or be dropped. */
    private fun keyBindingOk(
        contact: Contact,
        peerPublicKey: ByteArray,
    ): Boolean {
        val known = contactService.peerPublicKeyOf(contact.id)
        if (known == null) {
            contactService.bindPeerKey(contact.id, peerPublicKey)
            return true
        }
        return known.contentEquals(peerPublicKey)
    }

    private fun updateStatus(
        contactId: Long,
        messageId: Long,
        status: MessageStatus,
    ) {
        val updated =
            synchronized(store) {
                val list = store[contactId]
                val index = list?.indexOfFirst { it.id == messageId } ?: -1
                if (index < 0 || list == null) {
                    null
                } else {
                    val next = list[index].copy(status = status)
                    list[index] = next
                    next
                }
            }
        if (updated != null) notify(updated)
    }

    private fun notify(message: MessageItem) {
        listeners.forEach { it(message) }
    }

    private fun nextId(): Long = synchronized(store) { nextId++ }

    private fun nowLabel(): String = LocalTime.now().format(timeFormat)

    private companion object {
        const val CONNECT_TIMEOUT_MS = 30_000

        /** Default transport: through our own Tor node's SOCKS5 proxy to the onion service. */
        val defaultSocksSocket: (Int, String, Int) -> Socket = { socksPort, host, port ->
            Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))).apply {
                connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            }
        }
    }
}

/** Sliding one-minute window per peer — a leaked address must not become a spam channel. */
private class RateLimiter(
    private val maxPerMinute: Int,
) {
    private val events = mutableMapOf<String, MutableList<Long>>()

    fun allow(key: String): Boolean {
        val now = System.currentTimeMillis()
        val recent = events.getOrPut(key) { mutableListOf() }
        recent.removeAll { now - it > 60_000 }
        if (recent.size >= maxPerMinute) return false
        recent += now
        return true
    }
}
