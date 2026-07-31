package org.server.anonymous.business

/** A Tor v3 onion address — the user's identity ("phone number"). */
@JvmInline
value class OnionAddress(
    val value: String,
)
