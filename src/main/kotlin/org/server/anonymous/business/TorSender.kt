package org.server.anonymous.business

import java.net.Socket

/**
 * Sends one framed message to a peer's identity service over Tor and waits for the ACK.
 * Used for room fan-out and room control delivery; optionally pins the peer's static key
 * (safety-number check) when the expected key is known, as with room members.
 */
class TorSender(
    private val nodeStatus: () -> NodeStatus,
    private val keys: () -> X25519KeyPair,
    private val socketFactory: (Int, String, Int) -> Socket = TorSocket.factory,
) {
    @Suppress("ReturnCount", "TooGenericExceptionCaught", "SwallowedException") // delivery failures map to false
    fun send(
        address: String,
        expectedPeerKey: ByteArray?,
        contentType: Byte,
        body: ByteArray,
    ): Boolean {
        val online = nodeStatus() as? NodeStatus.Online ?: return false

        @Suppress("TooGenericExceptionCaught") // any connect failure just means "not delivered"
        val socket =
            try {
                socketFactory(online.socksPort, address, 80)
            } catch (t: Throwable) {
                return false
            }
        return try {
            val session = MessageSession.initiate(socket, keys(), online.address)
            try {
                if (expectedPeerKey != null && !expectedPeerKey.contentEquals(session.peerPublicKey)) {
                    return false
                }
                session.sendMessage(contentType, body)
                true
            } finally {
                session.close()
            }
        } catch (t: Throwable) {
            false
        } finally {
            runCatching { socket.close() }
        }
    }
}
