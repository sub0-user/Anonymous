package org.server.anonymous.business.model

import org.server.anonymous.business.OnionAddress

data class Contact(
    val id: Long,
    val alias: String,
    val address: OnionAddress,
    val lastActivityLabel: String? = null,
    /** The peer's static X25519 key, learned on first contact and bound to the safety number. */
    val peerPublicKey: ByteArray? = null,
)
