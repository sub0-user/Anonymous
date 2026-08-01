package org.server.anonymous.business

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class SafetyNumberTest {
    private val pubA = ByteArray(32) { it.toByte() }
    private val pubB = ByteArray(32) { (it + 100).toByte() }

    private val addressA = "a".repeat(56) + ".onion"
    private val addressB = "b".repeat(56) + ".onion"

    @Test
    fun `is symmetric for both peers`() {
        val fromA = SafetyNumber.of(addressA, pubA, addressB, pubB)
        val fromB = SafetyNumber.of(addressB, pubB, addressA, pubA)
        assertEquals(fromA, fromB)
    }

    @Test
    fun `changes when the peer key changes`() {
        val original = SafetyNumber.of(addressA, pubA, addressB, pubB)
        val impostor = SafetyNumber.of(addressA, pubA, addressB, pubB.copyOf().also { it[0] = 0 })
        assertFalse(original == impostor)
    }

    @Test
    fun `is stable for the same pair`() {
        val first = SafetyNumber.of(addressA, pubA, addressB, pubB)
        val second = SafetyNumber.of(addressA, pubA, addressB, pubB)
        assertEquals(first, second)
    }

    @Test
    fun `formats as 12 groups of 5 digits`() {
        val number = SafetyNumber.of(addressA, pubA, addressB, pubB)
        val groups = number.split(" ")
        assertEquals(12, groups.size)
        assertFalse(groups.any { it.length != 5 || !it.all(Char::isDigit) })
    }
}
