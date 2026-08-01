package org.server.anonymous.business

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IdentityKeysTest {
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun unhex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private val seedA = unhex("00".repeat(32))
    private val seedB = unhex("01".repeat(32))

    @Test
    fun `same seed derives the same keypair forever`() {
        val first = IdentityKeys.x25519KeyPairFromSeed(seedA)
        val second = IdentityKeys.x25519KeyPairFromSeed(seedA)
        assertArrayEquals(first.privateKey, second.privateKey)
        assertArrayEquals(first.publicKey, second.publicKey)
        assertEquals(32, first.privateKey.size)
        assertEquals(32, first.publicKey.size)
    }

    @Test
    fun `different seeds derive different keys`() {
        val a = IdentityKeys.x25519KeyPairFromSeed(seedA)
        val b = IdentityKeys.x25519KeyPairFromSeed(seedB)
        assertFalse(a.privateKey.contentEquals(b.privateKey))
        assertFalse(a.publicKey.contentEquals(b.publicKey))
    }

    @Test
    fun `shared secrets match on both sides`() {
        val a = IdentityKeys.x25519KeyPairFromSeed(seedA)
        val b = IdentityKeys.x25519KeyPairFromSeed(seedB)
        val ab = IdentityKeys.sharedSecret(a.privateKey, b.publicKey)
        val ba = IdentityKeys.sharedSecret(b.privateKey, a.publicKey)
        assertArrayEquals(ab, ba)
        assertEquals(32, ab.size)
    }

    /** RFC 7748 §6.1 official X25519 test vectors — proves the ECDH is spec-correct. */
    @Test
    fun `matches the RFC 7748 X25519 test vector`() {
        val alicePrivate = unhex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val alicePublic = unhex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
        val bobPrivate = unhex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val bobPublic = unhex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        val expected = unhex("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742")

        assertArrayEquals(expected, IdentityKeys.sharedSecret(alicePrivate, bobPublic))
        assertArrayEquals(expected, IdentityKeys.sharedSecret(bobPrivate, alicePublic))
    }

    @Test
    fun `rejects a low-order all-zero peer public key`() {
        // All-zero peer public key is a small-order point: the JDK rejects it outright
        // (InvalidKeyException) and IdentityKeys' own all-zero guard is a second line.
        val a = IdentityKeys.x25519KeyPairFromSeed(seedA)
        val zero = ByteArray(32)
        assertThrows(Exception::class.java) {
            IdentityKeys.sharedSecret(a.privateKey, zero)
        }
    }

    @Test
    fun `derived keys are not all zero and look random`() {
        val a = IdentityKeys.x25519KeyPairFromSeed(seedA)
        assertTrue(a.privateKey.any { it.toInt() != 0 })
        assertTrue(a.publicKey.any { it.toInt() != 0 })
        // The clamped scalar must have the X25519 clamping bits set.
        assertEquals(0, a.privateKey[0].toInt() and 0x07)
        assertTrue(a.privateKey[31].toInt() and 0x40 != 0)
        assertTrue(a.privateKey[31].toInt() and 0x80 == 0)
        assertTrue(hex(a.publicKey).isNotEmpty())
    }
}
