package org.server.anonymous.business

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.security.MessageDigest

/** One decrypted message delivered over a session. */
data class ReceivedMessage(
    val contentType: Byte,
    val body: ByteArray,
)

/**
 * One encrypted message exchange over a socket: HELLO handshake (sender address + static
 * X25519 key + a fresh session nonce), then a single DATA frame acknowledged by an ACK.
 * One connection = one message (Tor reuses circuits, so this stays cheap); the initiator
 * always sends, the responder always receives.
 */
class MessageSession private constructor(
    private val socket: Socket,
    private val staticKeys: X25519KeyPair,
    myAddress: String,
    isInitiator: Boolean,
) {
    val peerAddress: String
    val peerPublicKey: ByteArray

    private val keys: DirectionalKeys
    private val input: DataInputStream
    private val output: DataOutputStream

    companion object {
        fun initiate(
            socket: Socket,
            staticKeys: X25519KeyPair,
            myAddress: String,
        ): MessageSession = MessageSession(socket, staticKeys, myAddress, isInitiator = true)

        fun respond(
            socket: Socket,
            staticKeys: X25519KeyPair,
            myAddress: String,
        ): MessageSession = MessageSession(socket, staticKeys, myAddress, isInitiator = false)
    }

    init {
        check(myAddress.length <= WireProtocol.MAX_ADDRESS_LENGTH) { "address too long" }
        socket.soTimeout = 30_000
        input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

        // HELLO payload = [version:1][addressLen:1][address][static pub:32][session nonce:32].
        val sessionNonce = SessionCrypto.randomBytes(WireProtocol.SESSION_NONCE_LENGTH)
        val helloBody =
            byteArrayOf(WireProtocol.PROTOCOL_VERSION.toByte(), myAddress.length.toByte()) +
                myAddress.toByteArray(Charsets.UTF_8) +
                staticKeys.publicKey +
                sessionNonce
        WireProtocol.writeFrame(output, WireProtocol.TYPE_HELLO, helloBody)

        val peerHello = WireProtocol.readFrame(input)
        check(peerHello.type == WireProtocol.TYPE_HELLO) { "expected HELLO, got type ${peerHello.type}" }
        val parsed = parseHello(peerHello.payload)
        peerAddress = parsed.first
        peerPublicKey = parsed.second

        // ECDH with the static keys binds both identities; the fresh nonce pair salts the
        // HKDF so every session derives different keys even though the ECDH secret repeats.
        val shared = IdentityKeys.sharedSecret(staticKeys.privateKey, peerPublicKey)
        val saltInput = if (isInitiator) sessionNonce + parsed.third else parsed.third + sessionNonce
        val salt = MessageDigest.getInstance("SHA-256").digest(saltInput)
        keys = directionalKeys(SessionCrypto.sessionKeys(shared, salt, "anonymous/session/v1"), isInitiator)
    }

    /** Sends one message and blocks until the peer acknowledges it. */
    fun sendMessage(
        contentType: Byte,
        body: ByteArray,
    ) {
        check(1 + body.size <= WireProtocol.MAX_FRAME_SIZE - 1) { "message too large: ${body.size}" }
        val plaintext = byteArrayOf(contentType) + body
        val nonce = SessionCrypto.randomNonce()
        val ciphertext = SessionCrypto.encrypt(keys.outbound, nonce, plaintext, WireProtocol.AAD.toByteArray())
        WireProtocol.writeFrame(output, WireProtocol.TYPE_DATA, nonce + ciphertext)
        val ack = WireProtocol.readFrame(input)
        check(ack.type == WireProtocol.TYPE_ACK) { "expected ACK, got type ${ack.type}" }
    }

    /** Receives one message and acknowledges it. */
    fun receiveMessage(): ReceivedMessage {
        val frame = WireProtocol.readFrame(input)
        check(frame.type == WireProtocol.TYPE_DATA) { "expected DATA, got type ${frame.type}" }
        check(frame.payload.size > SessionCrypto.NONCE_LENGTH) { "DATA payload too short" }
        val nonce = frame.payload.copyOfRange(0, SessionCrypto.NONCE_LENGTH)
        val ciphertext = frame.payload.copyOfRange(SessionCrypto.NONCE_LENGTH, frame.payload.size)
        val plaintext = SessionCrypto.decrypt(keys.inbound, nonce, ciphertext, WireProtocol.AAD.toByteArray())
        WireProtocol.writeFrame(output, WireProtocol.TYPE_ACK)
        return ReceivedMessage(plaintext[0], plaintext.copyOfRange(1, plaintext.size))
    }

    fun close() {
        runCatching { socket.close() }
    }

    private fun parseHello(payload: ByteArray): Triple<String, ByteArray, ByteArray> {
        if (payload.size < 1 + 1 + 1 + 32 + 32) error("malformed HELLO")
        val version = payload[0].toInt() and 0xFF
        check(version == WireProtocol.PROTOCOL_VERSION) { "unsupported protocol version: $version" }
        val addressLen = payload[1].toInt() and 0xFF
        check(addressLen in 1..WireProtocol.MAX_ADDRESS_LENGTH) { "malformed address length: $addressLen" }
        check(payload.size == 2 + addressLen + 32 + 32) { "malformed HELLO length" }
        val address = payload.copyOfRange(2, 2 + addressLen).toString(Charsets.UTF_8)
        check(OnionAddressValidator.isValid(address)) { "malformed sender address" }
        val publicKey = payload.copyOfRange(2 + addressLen, 2 + addressLen + 32)
        val sessionNonce = payload.copyOfRange(2 + addressLen + 32, payload.size)
        return Triple(address, publicKey, sessionNonce)
    }
}
