package org.server.anonymous.business

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class ControlProtocolClientTest {
    private val executor = Executors.newCachedThreadPool()

    /** A scripted fake Tor control server: maps command prefixes to canned replies. */
    private fun fakeTorServer(
        port: Int,
        replies: Map<String, String>,
    ) {
        // Bind synchronously so the client never races ahead of the listener.
        val server = ServerSocket(port)
        executor.execute {
            server.use { srv ->
                val socket: Socket = srv.accept()
                socket.use { s ->
                    val reader = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII))
                    val writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), StandardCharsets.US_ASCII))
                    while (true) {
                        val line = reader.readLine() ?: break
                        val reply =
                            if (line.startsWith("PROTOCOLINFO")) {
                                "250-PROTOCOLINFO 1\n250-AUTH METHODS=COOKIE\n250 OK\n"
                            } else {
                                replies.entries.firstOrNull { line.startsWith(it.key) }?.value
                                    ?: "510 Unrecognized command\n"
                            }
                        writer.write(reply)
                        writer.flush()
                    }
                }
            }
        }
    }

    @Test
    fun `authenticates with the cookie`() {
        val port = freePort()
        fakeTorServer(port, mapOf("AUTHENTICATE" to "250 OK\n"))
        val client = ControlProtocolClient()
        client.connect("127.0.0.1", port)
        client.authenticate(byteArrayOf(0x01, 0x02))
        client.close()
    }

    @Test
    fun `parses bootstrap progress`() {
        val port = freePort()
        fakeTorServer(
            port,
            mapOf(
                "GETINFO status/bootstrap-phase" to
                    "250+status/bootstrap-phase=NOTICE BOOTSTRAP PROGRESS=90 TAG=handshake_done\n250 OK\n",
            ),
        )
        val client = ControlProtocolClient()
        client.connect("127.0.0.1", port)
        assertEquals(90, client.bootstrapProgress())
        client.close()
    }

    @Test
    fun `returns null progress when the reply has no progress`() {
        val port = freePort()
        fakeTorServer(port, mapOf("GETINFO" to "250 OK\n"))
        val client = ControlProtocolClient()
        client.connect("127.0.0.1", port)
        assertNull(client.bootstrapProgress())
        client.close()
    }

    @Test
    fun `adds an onion service and parses the address`() {
        val port = freePort()
        val seed = ByteArray(32) { it.toByte() }
        val expectedAddress = "a".repeat(56) + ".onion"
        fakeTorServer(
            port,
            mapOf(
                "ADD_ONION" to "250-ServiceID=${expectedAddress.removeSuffix(".onion")}\n250 OK\n",
            ),
        )
        val client = ControlProtocolClient()
        client.connect("127.0.0.1", port)
        val address = client.addOnionService(seed, virtualPort = 80, targetHost = "127.0.0.1", targetPort = 9000)
        assertEquals(expectedAddress, address)
        client.close()
    }

    @Test
    fun `surfaces tor errors`() {
        val port = freePort()
        fakeTorServer(port, mapOf("ADD_ONION" to "512 syntax error\n"))
        val client = ControlProtocolClient()
        client.connect("127.0.0.1", port)
        assertThrows(IllegalStateException::class.java) {
            client.addOnionService(ByteArray(32), 80, "127.0.0.1", 9000)
        }
        client.close()
    }

    @Test
    fun `tor key blob is 64 bytes with the seed prefix and a clamped scalar`() {
        val client = ControlProtocolClient()
        val seed = ByteArray(32) { it.toByte() }
        val blob = client.torKeyBlob(seed)
        assertEquals(64, blob.size)
        assertArrayEquals(seed, blob.copyOfRange(0, 32))
        // Clamp: low 3 bits of byte 0 cleared, bit 6 of byte 31 set, bit 7 cleared.
        assertEquals(0, blob[32].toInt() and 0x07)
        assertEquals(0x40, blob[63].toInt() and 0x40)
        assertEquals(0, blob[63].toInt() and 0x80)
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
}
