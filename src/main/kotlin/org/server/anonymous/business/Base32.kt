package org.server.anonymous.business

import java.io.ByteArrayOutputStream

/**
 * RFC 4648 base32 (A-Z, 2-7), unpadded. Used for public-room entry keys — a
 * copy-paste/type-friendly alphabet like onion addresses, without adding a dependency.
 */
object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                out.append(ALPHABET[(buffer ushr (bits - 5)) and 0x1F])
                bits -= 5
            }
        }
        if (bits > 0) out.append(ALPHABET[(buffer shl (5 - bits)) and 0x1F])
        return out.toString()
    }

    /** Strict decode: only alphabet characters, and any trailing partial block must be zero-padded. */
    fun decode(text: String): ByteArray {
        val chars = text.trim().uppercase()
        val out = ByteArrayOutputStream((chars.length * 5) / 8)
        var buffer = 0
        var bits = 0
        for (c in chars) {
            val value = ALPHABET.indexOf(c)
            check(value >= 0) { "invalid base32 character: $c" }
            buffer = (buffer shl 5) or value
            bits += 5
            if (bits >= 8) {
                out.write((buffer ushr (bits - 8)) and 0xFF)
                bits -= 8
            }
        }
        if (bits > 0) {
            check(bits < 5) { "invalid base32 length" }
            check(buffer and ((1 shl bits) - 1) == 0) { "invalid base32 padding" }
        }
        return out.toByteArray()
    }
}
