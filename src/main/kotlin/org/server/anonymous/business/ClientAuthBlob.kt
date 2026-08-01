package org.server.anonymous.business

import java.util.Base64

/**
 * Tor v3 client-auth keypairs ("invite mode"). X25519 keys, raw little-endian wire form:
 * the founder generates one pair per invite, sends the private half to the invitee (inside
 * the invite string), and registers the pair with `ADD_ONION ... ClientAuth=<blob>` so only
 * that invitee's Tor can reach the room service. `ClientAuth` blob = base64(public ‖ private);
 * the client-side file (`ClientOnionAuthDir/<serviceid>.auth_private`) = "x25519:<base64 priv>".
 */
data class ClientAuthKeyPair(
    val privateScalar: ByteArray,
    val publicU: ByteArray,
)

object ClientAuthBlob {
    /** Fresh random pair; the scalar is clamped like every X25519 key (see [IdentityKeys]). */
    fun createKeyPair(): ClientAuthKeyPair {
        val pair = IdentityKeys.x25519KeyPairFromSeed(SessionCrypto.randomBytes(32))
        return ClientAuthKeyPair(pair.privateKey, pair.publicKey)
    }

    /** The `ClientAuth` value for `ADD_ONION`: base64 of 32-byte public ‖ 32-byte private. */
    fun torAddOnionBlob(pair: ClientAuthKeyPair): String = b64(pair.publicU + pair.privateScalar)

    /** The contents of the invitee's `<serviceid>.auth_private` file under ClientOnionAuthDir. */
    fun authPrivateFileContent(privateScalar: ByteArray): String = "x25519:" + b64(privateScalar)

    fun parseAuthPrivateFile(content: String): ByteArray {
        val decoded = Base64.getDecoder().decode(content.trim().removePrefix("x25519:"))
        check(decoded.size == 32) { "invalid x25519 private key length" }
        return decoded
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}
