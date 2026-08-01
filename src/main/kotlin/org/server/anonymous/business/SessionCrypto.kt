package org.server.anonymous.business

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Symmetric session keys for the two directions (ChaCha20-Poly1305, 32-byte keys). */
data class SessionKeys(
    val sendKey: ByteArray,
    val receiveKey: ByteArray,
)

/** Direction-assigned keys for one connection: the initiator sends with the first half. */
data class DirectionalKeys(
    val outbound: ByteArray,
    val inbound: ByteArray,
)

fun directionalKeys(
    keys: SessionKeys,
    isInitiator: Boolean,
): DirectionalKeys =
    if (isInitiator) {
        DirectionalKeys(keys.sendKey, keys.receiveKey)
    } else {
        DirectionalKeys(keys.receiveKey, keys.sendKey)
    }

/** HKDF-SHA256 (RFC 5869) + ChaCha20-Poly1305 AEAD — the Phase 3 messaging crypto. */
object SessionCrypto {
    const val KEY_LENGTH = 32
    const val NONCE_LENGTH = 12

    /**
     * Both peers feed the same (shared secret, salt, info) so both derive the same
     * 64 bytes; the connection initiator sends with the first half, the responder
     * with the second — the direction convention makes both sides consistent.
     */
    fun sessionKeys(
        sharedSecret: ByteArray,
        salt: ByteArray,
        info: String,
    ): SessionKeys {
        val material = hkdf(sharedSecret, salt, info.toByteArray(Charsets.UTF_8), KEY_LENGTH * 2)
        return SessionKeys(
            sendKey = material.copyOfRange(0, KEY_LENGTH),
            receiveKey = material.copyOfRange(KEY_LENGTH, KEY_LENGTH * 2),
        )
    }

    fun randomNonce(): ByteArray = randomBytes(NONCE_LENGTH)

    fun randomBytes(length: Int): ByteArray = ByteArray(length).also { SecureRandom().nextBytes(it) }

    fun encrypt(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray,
    ): ByteArray =
        cipher(Cipher.ENCRYPT_MODE, key, nonce).let { c ->
            c.updateAAD(aad)
            c.doFinal(plaintext)
        }

    /** Throws [javax.crypto.AEADBadTagException] when the tag fails (tampered/forged input). */
    fun decrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray,
    ): ByteArray =
        cipher(Cipher.DECRYPT_MODE, key, nonce).let { c ->
            c.updateAAD(aad)
            c.doFinal(ciphertext)
        }

    /** RFC 5869 HKDF with a 32-byte salt and SHA-256. */
    fun hkdf(
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int,
    ): ByteArray {
        val prk = hmac(salt, ikm)
        var okm = ByteArray(0)
        var block = ByteArray(0)
        var counter = 1
        while (okm.size < length) {
            block = hmac(prk, block + info + counter.toByte())
            okm += block
            counter++
        }
        return okm.copyOf(length)
    }

    private fun cipher(
        mode: Int,
        key: ByteArray,
        nonce: ByteArray,
    ): Cipher =
        Cipher.getInstance("ChaCha20-Poly1305").apply {
            // The JDK's ChaCha20-Poly1305 takes the 96-bit nonce as an IvParameterSpec
            // (tag length is fixed at 128 bits; unlike AES-GCM there is no GCMParameterSpec).
            init(mode, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        }

    private fun hmac(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }
}
