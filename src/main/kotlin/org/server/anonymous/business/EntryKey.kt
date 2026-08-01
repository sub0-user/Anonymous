package org.server.anonymous.business

/**
 * Public-room door keys: 16 random bytes, base32-encoded for copy-paste. A public room's
 * service is open (no client auth), so the entry key is the only gate — the founder
 * publishes it together with the room URL and checks it at join time.
 */
object EntryKey {
    const val BYTE_LENGTH = 16

    fun generate(): String = Base32.encode(SessionCrypto.randomBytes(BYTE_LENGTH))

    fun isValid(key: String): Boolean {
        val decoded =
            runCatching { Base32.decode(key) }.getOrNull() ?: return false
        return decoded.size == BYTE_LENGTH
    }
}
