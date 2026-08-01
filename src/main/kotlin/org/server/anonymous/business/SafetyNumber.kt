package org.server.anonymous.business

import java.security.MessageDigest

/** Out-of-band verification fingerprint binding an onion address to a static messaging key. */
object SafetyNumber {
    private const val GROUPS = 12
    private const val GROUP_LENGTH = 5

    /**
     * Deterministic and symmetric: both peers hash the same sorted (address, key) pairs and
     * see the same number. Compare out-of-band once (call, meet) to defeat impersonation.
     */
    fun of(
        myAddress: String,
        myPublicKey: ByteArray,
        peerAddress: String,
        peerPublicKey: ByteArray,
    ): String {
        val digestInput =
            if (myAddress <= peerAddress) {
                myPublicKey + peerPublicKey + myAddress.toByteArray() + peerAddress.toByteArray()
            } else {
                peerPublicKey + myPublicKey + peerAddress.toByteArray() + myAddress.toByteArray()
            }
        val hash = MessageDigest.getInstance("SHA-256").digest(digestInput)
        val digits =
            buildString {
                for (b in hash) {
                    val v = b.toInt() and 0xFF
                    append(v / 100)
                    append((v / 10) % 10)
                    append(v % 10)
                }
            }
        return digits.take(GROUPS * GROUP_LENGTH).chunked(GROUP_LENGTH).joinToString(" ")
    }
}
