package org.server.anonymous.business

import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.NamedParameterSpec
import java.security.spec.XECPrivateKeySpec
import java.security.spec.XECPublicKeySpec
import javax.crypto.KeyAgreement

/** A 32-byte X25519 key pair (private scalar, public u-coordinate). */
data class X25519KeyPair(
    val privateKey: ByteArray,
    val publicKey: ByteArray,
)

/**
 * Derives the user's static X25519 messaging key from the Ed25519 identity seed.
 * Same seed -> same key forever, so identity export/import only carries the seed.
 * Private scalar: clamp(SHA-512(seed)[0:32]); public key: X25519(scalar, base point u=9).
 */
object IdentityKeys {
    fun x25519KeyPairFromSeed(seed: ByteArray): X25519KeyPair {
        val scalar = clampScalar(MessageDigest.getInstance("SHA-512").digest(seed).copyOf(32))
        val factory = KeyFactory.getInstance("XDH")
        val privateKey = factory.generatePrivate(XECPrivateKeySpec(NamedParameterSpec.X25519, scalar))
        val basePoint = factory.generatePublic(XECPublicKeySpec(NamedParameterSpec.X25519, BigInteger.valueOf(9)))
        val agreement = KeyAgreement.getInstance("X25519")
        agreement.init(privateKey)
        agreement.doPhase(basePoint, true)
        return X25519KeyPair(scalar, agreement.generateSecret())
    }

    /**
     * ECDH shared secret. X25519 wire bytes are little-endian, so the peer's public
     * u-coordinate is reversed before the JDK's big-endian BigInteger expects it.
     */
    fun sharedSecret(
        myPrivateKey: ByteArray,
        peerPublicKey: ByteArray,
    ): ByteArray {
        val factory = KeyFactory.getInstance("XDH")
        val privateKey = factory.generatePrivate(XECPrivateKeySpec(NamedParameterSpec.X25519, myPrivateKey))
        val peerU = BigInteger(1, peerPublicKey.reversedArray())
        val publicKey = factory.generatePublic(XECPublicKeySpec(NamedParameterSpec.X25519, peerU))
        val agreement = KeyAgreement.getInstance("X25519")
        agreement.init(privateKey)
        agreement.doPhase(publicKey, true)
        val secret = agreement.generateSecret()
        check(secret.any { it.toInt() != 0 }) { "X25519 produced a low-order shared secret" }
        return secret
    }

    /** The little-endian u-coordinate for an already-clamped scalar (e.g. a client-auth key). */
    fun x25519PublicKeyFromScalar(privateScalar: ByteArray): ByteArray {
        val factory = KeyFactory.getInstance("XDH")
        val privateKey = factory.generatePrivate(XECPrivateKeySpec(NamedParameterSpec.X25519, privateScalar))
        val basePoint = factory.generatePublic(XECPublicKeySpec(NamedParameterSpec.X25519, BigInteger.valueOf(9)))
        val agreement = KeyAgreement.getInstance("X25519")
        agreement.init(privateKey)
        agreement.doPhase(basePoint, true)
        return agreement.generateSecret()
    }

    private fun clampScalar(input: ByteArray): ByteArray {
        val s = input.copyOf()
        s[0] = (s[0].toInt() and 0xF8).toByte()
        s[31] = (s[31].toInt() and 0x7F).toByte()
        s[31] = (s[31].toInt() or 0x40).toByte()
        return s
    }
}
