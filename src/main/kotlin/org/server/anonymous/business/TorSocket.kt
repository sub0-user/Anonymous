package org.server.anonymous.business

import java.net.Socket

/**
 * The shared outbound transport: a manual SOCKS5 client through our own Tor node. The JDK's
 * `java.net` SOCKS proxy hides failures and can block forever during negotiation, so every
 * peer connection goes through [Socks5], which surfaces Tor's error codes and respects the
 * connect timeout.
 */
object TorSocket {
    const val CONNECT_TIMEOUT_MS = 90_000

    /** First onion connection on a fresh circuit can take a while — be generous. */
    val factory: (Int, String, Int) -> Socket = { socksPort, host, port ->
        Socks5.connect(socksPort, host, port, CONNECT_TIMEOUT_MS)
    }
}
