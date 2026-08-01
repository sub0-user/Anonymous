package org.server.anonymous.business

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import javax.crypto.AEADBadTagException

class SessionCryptoTest {
    private fun unhex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private val key = ByteArray(32) { it.toByte() }
    private val nonce = ByteArray(SessionCrypto.NONCE_LENGTH) { (it + 1).toByte() }
    private val aad = "anonymous/text/v1".toByteArray()

    /** RFC 5869 appendix A.1 — official HKDF-SHA256 test vector. */
    @Test
    fun `hkdf matches the RFC 5869 test vector`() {
        val ikm = unhex("0b".repeat(22))
        val salt = unhex("000102030405060708090a0b0c")
        val info = unhex("f0f1f2f3f4f5f6f7f8f9")
        val expected =
            unhex(
                "3cb25f25faacd57a90434f64d0362f2a" +
                    "2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                    "34007208d5b887185865",
            )
        assertArrayEquals(expected, SessionCrypto.hkdf(ikm, salt, info, 42))
    }

    @Test
    fun `encrypt and decrypt roundtrip with aad`() {
        val plaintext = "hello from the onion".toByteArray()
        val ciphertext = SessionCrypto.encrypt(key, nonce, plaintext, aad)
        assertFalse(ciphertext.contentEquals(plaintext))
        val restored = SessionCrypto.decrypt(key, nonce, ciphertext, aad)
        assertArrayEquals(plaintext, restored)
    }

    @Test
    fun `tampered ciphertext is rejected`() {
        val plaintext = "tamper me".toByteArray()
        val ciphertext = SessionCrypto.encrypt(key, nonce, plaintext, aad)
        val tampered = ciphertext.copyOf().also { it[5] = (it[5].toInt() xor 0x01).toByte() }
        assertThrows(AEADBadTagException::class.java) {
            SessionCrypto.decrypt(key, nonce, tampered, aad)
        }
    }

    @Test
    fun `wrong aad or wrong key is rejected`() {
        val plaintext = "context matters".toByteArray()
        val ciphertext = SessionCrypto.encrypt(key, nonce, plaintext, aad)
        assertThrows(AEADBadTagException::class.java) {
            SessionCrypto.decrypt(key, nonce, ciphertext, "wrong-context".toByteArray())
        }
        assertThrows(AEADBadTagException::class.java) {
            SessionCrypto.decrypt(ByteArray(32), nonce, ciphertext, aad)
        }
    }

    @Test
    fun `session keys are deterministic and directionally split`() {
        val secret = ByteArray(32) { it.toByte() }
        val salt = ByteArray(64) { (it * 3).toByte() }
        val a = SessionCrypto.sessionKeys(secret, salt, "anonymous/session/v1")
        val b = SessionCrypto.sessionKeys(secret, salt, "anonymous/session/v1")
        assertArrayEquals(a.sendKey, b.sendKey)
        assertArrayEquals(a.receiveKey, b.receiveKey)
        assertFalse(a.sendKey.contentEquals(a.receiveKey))
        assertEquals(32, a.sendKey.size)
        assertEquals(32, a.receiveKey.size)
    }

    @Test
    fun `different info derives different keys`() {
        val secret = ByteArray(32) { it.toByte() }
        val salt = ByteArray(64)
        val v1 = SessionCrypto.sessionKeys(secret, salt, "anonymous/session/v1")
        val v2 = SessionCrypto.sessionKeys(secret, salt, "anonymous/session/v2")
        assertFalse(v1.sendKey.contentEquals(v2.sendKey))
    }

    @Test
    fun `nonces are unique and correctly sized`() {
        val nonceA = SessionCrypto.randomNonce()
        val nonceB = SessionCrypto.randomNonce()
        assertEquals(SessionCrypto.NONCE_LENGTH, nonceA.size)
        assertFalse(nonceA.contentEquals(nonceB))
    }
}
