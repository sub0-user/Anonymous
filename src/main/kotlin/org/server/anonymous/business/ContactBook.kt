package org.server.anonymous.business

import org.server.anonymous.business.model.Contact
import org.server.anonymous.business.model.ContactRequest
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.Properties

/**
 * The real contact store: starts empty, tracks requests, blocks and peer keys.
 * Contacts, blocks and safety-number bindings persist to one 0600 properties file
 * (Phase A1 pattern); requests stay ephemeral. A null file keeps in-memory-only
 * behavior for tests.
 */
@Suppress("TooManyFunctions") // same surface as ContactService, by contract
class ContactBook(
    private val contactsFile: java.nio.file.Path? = null,
) : ContactService {
    private val contacts = mutableListOf<Contact>()
    private val requests = mutableListOf<ContactRequest>()
    private val blocked = mutableSetOf<String>()
    private val peerKeys = mutableMapOf<Long, ByteArray>()
    private var nextId = 1L

    init {
        contactsFile?.let { load(it) }
    }

    override fun listContacts(): List<Contact> = contacts.toList()

    override fun addContact(
        alias: String,
        address: String,
    ): OpResult<Contact> {
        val trimmedAlias = alias.trim()
        val trimmedAddress = address.trim()
        val failure =
            when {
                trimmedAlias.isEmpty() -> OpResult.Failure("Alias is required")
                !OnionAddressValidator.isValid(trimmedAddress) -> OpResult.Failure("Invalid v3 onion address")
                contacts.any { it.address.value == trimmedAddress } ->
                    OpResult.Failure("Contact with this address already exists")
                else -> null
            }
        if (failure != null) return failure
        val contact = Contact(nextId++, trimmedAlias, OnionAddress(trimmedAddress))
        contacts += contact
        persist()
        return OpResult.Success(contact)
    }

    override fun findByAddress(address: String): Contact? = contacts.firstOrNull { it.address.value == address }

    override fun deleteContact(id: Long): Boolean {
        val removed = contacts.removeAll { it.id == id }
        if (removed) persist()
        return removed
    }

    override fun isBlocked(address: String): Boolean = blocked.contains(address)

    override fun block(address: String) {
        blocked += address
        requests.removeAll { it.address.value == address }
        persist()
    }

    override fun unblock(address: String) {
        blocked -= address
        persist()
    }

    override fun incomingRequests(): List<ContactRequest> = requests.toList()

    override fun addRequest(
        address: String,
        preview: String,
    ) {
        if (contacts.any { it.address.value == address } || requests.any { it.address.value == address }) return
        requests += ContactRequest(nextId++, OnionAddress(address), preview, "now")
    }

    override fun acceptRequest(address: String): OpResult<Contact> {
        val request =
            requests.firstOrNull { it.address.value == address }
                ?: return OpResult.Failure("No request from this address")
        requests.remove(request)
        return addContact(address.take(12), address)
    }

    override fun ignoreRequest(address: String) {
        requests.removeAll { it.address.value == address }
    }

    override fun peerPublicKeyOf(contactId: Long): ByteArray? = peerKeys[contactId]

    override fun bindPeerKey(
        contactId: Long,
        key: ByteArray,
    ) {
        peerKeys[contactId] = key
        // Keep the Contact model in sync so backups export the safety-number binding too.
        val index = contacts.indexOfFirst { it.id == contactId }
        if (index >= 0) contacts[index] = contacts[index].copy(peerPublicKey = key)
        persist()
    }

    override fun blockedAddresses(): List<String> = blocked.toList()

    /** Restores the contact list + block list from a backup; ids come back exactly as backed up. */
    override fun restore(
        contacts: List<Contact>,
        blocked: List<String>,
    ) {
        this.contacts.clear()
        this.contacts += contacts
        this.blocked.clear()
        this.blocked += blocked
        peerKeys.clear()
        contacts.forEach { contact -> contact.peerPublicKey?.let { peerKeys[contact.id] = it } }
        val maxId = contacts.maxOfOrNull { it.id } ?: 0L
        if (maxId >= nextId) nextId = maxId + 1
        persist()
    }

    /** Restores contacts, blocks and peer-key bindings from the properties file. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // a corrupt file starts empty, never fatal
    private fun load(path: Path) {
        if (!Files.exists(path)) return
        val props =
            runCatching {
                Properties().apply {
                    Files.newInputStream(path).use { load(it) }
                }
            }.getOrNull() ?: return
        val count = props.getProperty("contact.count")?.toIntOrNull() ?: 0
        for (i in 0 until count) {
            val contact = readContact(props, i) ?: continue
            contacts += contact
            contact.peerPublicKey?.let { peerKeys[contact.id] = it }
            if (contact.id >= nextId) nextId = contact.id + 1
        }
        val blockedCount = props.getProperty("blocked.count")?.toIntOrNull() ?: 0
        for (i in 0 until blockedCount) {
            props.getProperty("blocked.$i")?.let { blocked += it }
        }
    }

    /**
     * Parses one contact row; null means the row is malformed and is skipped.
     * @Suppress ReturnCount: each early return is a per-field validation guard.
     */
    @Suppress("ReturnCount")
    private fun readContact(
        props: Properties,
        i: Int,
    ): Contact? {
        val p = "contact.$i."
        val id = props.getProperty("${p}id")?.toLongOrNull() ?: return null
        val alias = props.getProperty("${p}alias") ?: return null
        val address = props.getProperty("${p}address") ?: return null
        if (!OnionAddressValidator.isValid(address)) return null
        val peerKey =
            props.getProperty("${p}peerKey")?.let { raw ->
                runCatching { Base64.getDecoder().decode(raw) }.getOrNull()
            }
        return Contact(id, alias, OnionAddress(address), peerPublicKey = peerKey)
    }

    /** Writes contacts + blocks + peer-key bindings (best-effort — never break an in-memory op). */
    @Suppress("SwallowedException") // a failed disk write must not fail the UI operation
    private fun persist() {
        val file = contactsFile ?: return
        runCatching {
            Files.createDirectories(file.parent)
            PrivateFileOps.setPrivateDir(file.parent)
            val props = Properties()
            props.setProperty("contact.count", contacts.size.toString())
            contacts.forEachIndexed { i, contact ->
                val p = "contact.$i."
                props.setProperty("${p}id", contact.id.toString())
                props.setProperty("${p}alias", contact.alias)
                props.setProperty("${p}address", contact.address.value)
                contact.peerPublicKey?.let { props.setProperty("${p}peerKey", Base64.getEncoder().encodeToString(it)) }
            }
            props.setProperty("blocked.count", blocked.size.toString())
            blocked.forEachIndexed { i, address -> props.setProperty("blocked.$i", address) }
            Files.newOutputStream(file).use { props.store(it, "Anonymous contacts — do not share") }
            PrivateFileOps.setPrivateFile(file)
        }
    }
}
