package org.server.anonymous.business

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EntryKeyTest {
    @Test
    fun `generated key is valid and decodes to 16 bytes`() {
        val key = EntryKey.generate()
        assertTrue(EntryKey.isValid(key))
        assertEquals(EntryKey.BYTE_LENGTH, Base32.decode(key).size)
    }

    @Test
    fun `two generated keys differ`() {
        assertFalse(EntryKey.generate() == EntryKey.generate())
    }

    @Test
    fun `tampered keys are rejected`() {
        val key = EntryKey.generate()
        // The final base32 char carries 2 padding bits: any of F/G/H (values 5-7) sets
        // a nonzero pad, so the key no longer decodes to exactly 16 clean bytes.
        val badPadding = key.dropLast(1) + listOf("F", "G", "H").first { it != key.last().toString() }
        assertFalse(EntryKey.isValid(badPadding))
        assertFalse(EntryKey.isValid(""))
        assertFalse(EntryKey.isValid("SHORT"))
        assertFalse(EntryKey.isValid("0" + key.drop(1))) // '0' is not in the alphabet
    }
}
