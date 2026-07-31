package org.server.anonymous.business

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OnionAddressValidatorTest {
    private val alphabet = "abcdefghijklmnopqrstuvwxyz234567"

    private fun validAddress(): String = (1..56).map { alphabet[it % alphabet.length] }.joinToString("") + ".onion"

    @Test
    fun `accepts a valid v3 onion address`() {
        assertTrue(OnionAddressValidator.isValid(validAddress()))
    }

    @Test
    fun `rejects an address that is too short`() {
        assertFalse(OnionAddressValidator.isValid("short.onion"))
    }

    @Test
    fun `rejects invalid characters (uppercase)`() {
        val bad = "A" + "a".repeat(55) + ".onion"
        assertFalse(OnionAddressValidator.isValid(bad))
    }

    @Test
    fun `rejects a missing onion suffix`() {
        assertFalse(OnionAddressValidator.isValid("a".repeat(56)))
    }

    @Test
    fun `rejects an onion suffix in the wrong position`() {
        assertFalse(OnionAddressValidator.isValid("a".repeat(50) + ".onion" + "a".repeat(2)))
    }
}
