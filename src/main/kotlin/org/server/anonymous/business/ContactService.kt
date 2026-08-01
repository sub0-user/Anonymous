package org.server.anonymous.business

import org.server.anonymous.business.model.Contact
import org.server.anonymous.business.model.ContactRequest

/**
 * One cohesive contact-store surface (queries + requests + blocks + peer keys). Persistence
 * is Phase 4. @Suppress TooManyFunctions: splitting it would ripple without adding clarity.
 */
@Suppress("TooManyFunctions")
interface ContactService {
    fun listContacts(): List<Contact>

    fun addContact(
        alias: String,
        address: String,
    ): OpResult<Contact>

    fun findByAddress(address: String): Contact?

    fun deleteContact(id: Long): Boolean

    fun isBlocked(address: String): Boolean

    fun block(address: String)

    fun unblock(address: String)

    fun incomingRequests(): List<ContactRequest>

    fun addRequest(
        address: String,
        preview: String,
    )

    /** Accepts a request and promotes it to a contact; returns the new contact. */
    fun acceptRequest(address: String): OpResult<Contact>

    fun ignoreRequest(address: String)

    fun peerPublicKeyOf(contactId: Long): ByteArray?

    fun bindPeerKey(
        contactId: Long,
        key: ByteArray,
    )
}
