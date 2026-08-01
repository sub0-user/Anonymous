package org.server.anonymous.business

import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Minimal SOCKS5 client (no-auth) for routing to .onion services through our Tor node.
 * Written by hand instead of java.net's proxy because every step must be timeout-bounded
 * and Tor's connect error codes must surface — the JDK's SOCKS handshake can block
 * indefinitely and hides failures behind "SENT forever".
 */
object Socks5 {
    @Suppress("TooGenericExceptionCaught") // always close the half-open socket, then rethrow
    fun connect(
        socksPort: Int,
        host: String,
        port: Int,
        timeoutMs: Int,
    ): Socket {
        val socket = Socket()
        try {
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress("127.0.0.1", socksPort), timeoutMs)
            val out = socket.getOutputStream()
            val input = socket.getInputStream()
            out.write(byteArrayOf(0x05, 0x01, 0x00)) // version 5, one method, no-auth
            out.flush()
            val greeting = readExact(input, 2)
            check(greeting[0].toInt() == 0x05 && greeting[1].toInt() == 0x00) { "SOCKS5: proxy demands auth" }
            val hostBytes = host.toByteArray(Charsets.UTF_8)
            check(hostBytes.size <= 255) { "SOCKS5: host too long" }
            val command =
                byteArrayOf(0x05, 0x01, 0x00, 0x03, hostBytes.size.toByte()) +
                    hostBytes +
                    byteArrayOf((port shr 8).toByte(), (port and 0xFF).toByte())
            out.write(command)
            out.flush()
            val reply = readExact(input, 4)
            check(reply[0].toInt() == 0x05) { "SOCKS5: bad version" }
            check(reply[1].toInt() == 0x00) { "SOCKS5: tor refused the connection (code ${reply[1].toInt()})" }
            drainBindAddress(input, reply[3].toInt())
            return socket
        } catch (t: Throwable) {
            // Always close the half-open socket before surfacing the real cause.
            runCatching { socket.close() }
            throw t
        }
    }

    private fun drainBindAddress(
        input: InputStream,
        addressType: Int,
    ) {
        when (addressType) {
            1 -> readExact(input, 4 + 2) // IPv4 + port
            4 -> readExact(input, 16 + 2) // IPv6 + port
            3 -> {
                val length = readExact(input, 1)[0].toInt() and 0xFF
                readExact(input, length + 2)
            }
            else -> error("SOCKS5: unsupported bind address type $addressType")
        }
    }

    private fun readExact(
        input: InputStream,
        length: Int,
    ): ByteArray {
        val data = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(data, offset, length - offset)
            if (read < 0) throw IOException("SOCKS5: connection closed")
            offset += read
        }
        return data
    }
}
