package org.server.anonymous.business

import org.server.anonymous.business.model.Contact
import org.server.anonymous.business.model.MessageDirection
import org.server.anonymous.business.model.MessageItem
import org.server.anonymous.business.model.MessageStatus
import java.net.ServerSocket
import java.net.Socket
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Real Phase 3 messaging: sends text over Tor (via our SOCKS port) to a contact's onion
 * service and receives inbound messages on the node's reserved listener. In-memory store;
 * contacts, requests, blocks and peer-key binding come from [ContactService]. Persistence
 * is Phase 4.
 *
 * @Suppress TooManyFunctions, LongParameterList: a transport service is naturally many small
 * cohesive steps and takes its collaborators directly; splitting them would hide the flow.
 */
@Suppress("TooManyFunctions", "LongParameterList")
class P2pMessageService(
    private val contactService: ContactService,
    private val nodeStatus: () -> NodeStatus,
    private val inboundSocket: () -> ServerSocket?,
    private val identity: () -> Identity,
    private val socketFactory: (Int, String, Int) -> Socket = defaultSocksSocket,
    private val rateLimitPerMinute: Int = 30,
    private val roomInbound: (ByteArray, String, Byte, ByteArray) -> Unit = { _, _, _, _ -> },
    /** Encrypted at-rest history (Phase A1); null keeps the in-memory-only behavior for tests. */
    private val messageHistory: MessageJournal<MessageItem>? = null,
    /** How often the offline outbox is rescanned (Phase A2); short in tests, 30s in the app. */
    private val retryScanMillis: Long = 30_000,
    /** Base of the retry backoff ladder (1x/5x/30x, capped at 60x); short in tests. */
    private val retryBackoffBaseMillis: Long = 60_000,
) : MessageService {
    private val store = mutableMapOf<Long, MutableList<MessageItem>>()
    private val listeners = CopyOnWriteArrayList<(MessageItem) -> Unit>()
    private val senderExecutor =
        Executors.newSingleThreadExecutor { r -> Thread(r, "msg-send").apply { isDaemon = true } }
    private val listenerExecutor =
        Executors.newSingleThreadExecutor { r -> Thread(r, "msg-listen").apply { isDaemon = true } }
    private val retryExecutor =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "msg-retry").apply { isDaemon = true } }
    private val rateLimit = RateLimiter(maxPerMinute = rateLimitPerMinute)
    private var listenerStarted = false
    private var nextId = 1L

    /** Outgoing messages waiting on the peer: message id -> retry state (Phase A2 outbox). */
    private val pending = mutableMapOf<Long, PendingDelivery>()

    private val keys: X25519KeyPair by lazy { IdentityKeys.x25519KeyPairFromSeed(identity().seed) }
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

    init {
        // Restore persisted history; a later record for the same id (a status change) wins.
        messageHistory?.load()?.forEach { (contactId, item) ->
            val list = store.getOrPut(contactId) { mutableListOf() }
            val index = list.indexOfLast { it.id == item.id }
            if (index >= 0) list[index] = item else list += item
            if (item.id >= nextId) nextId = item.id + 1
        }
        // Any persisted outgoing message still waiting on the peer is retried after a restart.
        synchronized(store) {
            for ((contactId, list) in store) {
                for (item in list) {
                    if (item.direction == MessageDirection.OUT && item.status == MessageStatus.SENT) {
                        pending[item.id] = PendingDelivery(contactId, attempts = 0, nextRetryAtMillis = now())
                    }
                }
            }
        }
        retryExecutor.scheduleWithFixedDelay(
            { runCatching { senderExecutor.execute { processDue() } } },
            retryScanMillis,
            retryScanMillis,
            TimeUnit.MILLISECONDS,
        )
    }

    override fun messagesFor(contactId: Long): List<MessageItem> =
        synchronized(store) {
            store[contactId]?.toList() ?: emptyList()
        }

    override fun addMessageListener(listener: (MessageItem) -> Unit) {
        listeners += listener
    }

    /** Deletes this conversation's history from disk and memory. */
    override fun clearHistory(contactId: Long) {
        synchronized(store) { store.remove(contactId) }
        synchronized(pending) {
            pending.entries.removeAll { it.value.contactId == contactId }
        }
        messageHistory?.clear(contactId)
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
        messageHistory?.append(contactId, message)
        notify(message)
        // The outbox owns delivery: enqueue now, attempt immediately, retry with backoff while offline.
        synchronized(pending) {
            pending[message.id] = PendingDelivery(contactId, attempts = 0, nextRetryAtMillis = now())
        }
        senderExecutor.execute { processDue() }
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
        retryExecutor.shutdownNow()
    }

    /**
     * Delivers every outbox entry that is due. Runs on the single sender thread, so no two
     * attempts for the same message can overlap; a fresh attempt that fails just reschedules
     * with a longer backoff instead of giving up.
     */
    private fun processDue() {
        val due =
            synchronized(pending) {
                pending
                    .filter { (_, p) -> p.nextRetryAtMillis <= now() }
                    .map { (messageId, p) -> messageId to p.contactId }
            }
        for ((messageId, contactId) in due) {
            val outcome = attemptOutbox(messageId, contactId)
            when (outcome) {
                null -> synchronized(pending) { pending.remove(messageId) }
                DeliveryOutcome.DELIVERED -> {
                    updateStatus(contactId, messageId, MessageStatus.DELIVERED)
                    synchronized(pending) { pending.remove(messageId) }
                }
                DeliveryOutcome.PERMANENT -> {
                    updateStatus(contactId, messageId, MessageStatus.FAILED)
                    synchronized(pending) { pending.remove(messageId) }
                }
                DeliveryOutcome.RETRY -> reschedule(messageId)
            }
        }
    }

    /** Looks the outbox entry up and makes one attempt; null means the entry is obsolete. */
    private fun attemptOutbox(
        messageId: Long,
        contactId: Long,
    ): DeliveryOutcome? {
        val contact = contactService.listContacts().firstOrNull { it.id == contactId }
        val message =
            if (contact == null) {
                null
            } else {
                synchronized(store) { store[contactId]?.firstOrNull { it.id == messageId } }
            }
        return if (contact == null || message == null) null else deliverOnce(contact, message)
    }

    /** Backs off 1 min -> 5 min -> 30 min, capped at an hour — an offline peer is never flooded. */
    private fun reschedule(messageId: Long) {
        synchronized(pending) {
            val entry = pending[messageId] ?: return
            entry.attempts += 1
            entry.nextRetryAtMillis = now() + backoffMillis(entry.attempts)
        }
    }

    private fun backoffMillis(attempt: Int): Long =
        when {
            attempt <= 1 -> retryBackoffBaseMillis
            attempt == 2 -> retryBackoffBaseMillis * 5
            attempt == 3 -> retryBackoffBaseMillis * 30
            else -> retryBackoffBaseMillis * 60
        }

    /**
     * One delivery attempt. Connectivity problems are RETRY (the outbox keeps trying); a peer
     * whose static key no longer matches the bound safety number is PERMANENT — retrying an
     * identity change would be a security failure, not a delivery problem.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // any connect failure just means "retry later"
    private fun deliverOnce(
        contact: Contact,
        message: MessageItem,
    ): DeliveryOutcome =
        try {
            val online = nodeStatus() as? NodeStatus.Online ?: return DeliveryOutcome.RETRY
            val socket = socketFactory(online.socksPort, contact.address.value, 80)
            try {
                sendViaSession(socket, online.address, contact, message)
            } finally {
                socket.close()
            }
        } catch (t: Throwable) {
            DeliveryOutcome.RETRY
        }

    private fun sendViaSession(
        socket: Socket,
        myAddress: String,
        contact: Contact,
        message: MessageItem,
    ): DeliveryOutcome {
        val session = MessageSession.initiate(socket, keys, myAddress)
        try {
            val known = contactService.peerPublicKeyOf(contact.id)
            if (known != null && !known.contentEquals(session.peerPublicKey)) {
                return DeliveryOutcome.PERMANENT
            }
            contactService.bindPeerKey(contact.id, session.peerPublicKey)
            session.sendMessage(WireProtocol.CONTENT_TEXT.toByte(), message.body.toByteArray())
            return DeliveryOutcome.DELIVERED
        } finally {
            session.close()
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
        when (received.contentType.toInt()) {
            WireProtocol.CONTENT_TEXT -> handleTextInbound(session, received)
            WireProtocol.CONTENT_ROOM_MSG, WireProtocol.CONTENT_ROOM_CONTROL ->
                roomInbound(session.peerPublicKey, session.peerAddress, received.contentType, received.body)
            else -> Unit
        }
    }

    private fun handleTextInbound(
        session: MessageSession,
        received: ReceivedMessage,
    ) {
        val text = received.body.toString(Charsets.UTF_8)
        val contact = contactService.findByAddress(session.peerAddress)
        when {
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
        messageHistory?.append(contact.id, message)
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
        if (updated != null) {
            messageHistory?.append(contactId, updated)
            notify(updated)
        }
    }

    private fun notify(message: MessageItem) {
        listeners.forEach { it(message) }
    }

    private fun nextId(): Long = synchronized(store) { nextId++ }

    private fun nowLabel(): String = LocalTime.now().format(timeFormat)

    private fun now(): Long = System.currentTimeMillis()

    private companion object {
        /** Default transport: through our own Tor node's SOCKS5 proxy to the onion service. */
        val defaultSocksSocket: (Int, String, Int) -> Socket = TorSocket.factory
    }
}

/** One outbox entry: where to deliver and how far the backoff has climbed. */
private data class PendingDelivery(
    val contactId: Long,
    var attempts: Int,
    var nextRetryAtMillis: Long,
)

/** What a single delivery attempt tells the outbox. */
private enum class DeliveryOutcome {
    /** The peer acknowledged the message — stop retrying. */
    DELIVERED,

    /** Transient connectivity problem — keep the message queued and back off. */
    RETRY,

    /** The peer's key no longer matches the bound safety number — never retry. */
    PERMANENT,
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
