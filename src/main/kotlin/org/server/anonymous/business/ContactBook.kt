package org.server.anonymous.business

import org.server.anonymous.business.model.Contact
import org.server.anonymous.business.model.ContactRequest

/**
 * The real contact store: starts empty, tracks requests, blocks and peer keys in memory
 * (persistence arrives in Phase 4). The seeded InMemoryContactService remains for UI tests.
 */
@Suppress("TooManyFunctions") // same surface as ContactService, by contract
class ContactBook : ContactService {
    private val contacts = mutableListOf<Contact>()
    private val requests = mutableListOf<ContactRequest>()
    private val blocked = mutableSetOf<String>()
    private val peerKeys = mutableMapOf<Long, ByteArray>()
    private var nextId = 1L

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
        return OpResult.Success(contact)
    }

    override fun findByAddress(address: String): Contact? = contacts.firstOrNull { it.address.value == address }

    override fun deleteContact(id: Long): Boolean = contacts.removeAll { it.id == id }

    override fun isBlocked(address: String): Boolean = blocked.contains(address)

    override fun block(address: String) {
        blocked += address
        requests.removeAll { it.address.value == address }
    }

    override fun unblock(address: String) {
        blocked -= address
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
    }
}
