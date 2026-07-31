package org.server.anonymous.business

import org.server.anonymous.business.model.Contact

/** Mock contact store — replaced by a real repository in Phase 4. */
class InMemoryContactService : ContactService {
    private val contacts =
        mutableListOf(
            Contact(1, "raven", OnionAddress(mockAddress(1)), "2m ago"),
            Contact(2, "ghost", OnionAddress(mockAddress(2)), "1h ago"),
            Contact(3, "moth", OnionAddress(mockAddress(3)), "yesterday"),
        )

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
        val contact = Contact(nextId(), trimmedAlias, OnionAddress(trimmedAddress), null)
        contacts += contact
        return OpResult.Success(contact)
    }

    private fun nextId(): Long = (contacts.maxOfOrNull { it.id } ?: 0L) + 1

    private fun mockAddress(seed: Int): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz234567"
        return buildString {
            repeat(56) { i -> append(alphabet[(seed + i) % alphabet.length]) }
        } + ".onion"
    }
}
