package org.server.anonymous.business.model

import org.server.anonymous.business.OnionAddress

/** An inbound message from an address that is not yet a contact — the approval gate. */
data class ContactRequest(
    val id: Long,
    val address: OnionAddress,
    val preview: String,
    val receivedAtLabel: String,
)
