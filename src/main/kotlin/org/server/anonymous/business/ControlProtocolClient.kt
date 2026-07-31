package org.server.anonymous.business

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Minimal Tor control-protocol client (see guide/dev/tor-control.md).
 * LF-terminated lines; multi-line replies end with "250 OK"; "5xx" lines are errors;
 * "650 " lines are async events and are skipped.
 */
class ControlProtocolClient : TorControl {
    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null

    override fun connect(
        host: String,
        port: Int,
    ) {
        val s = Socket(host, port)
        socket = s
        reader = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII))
        writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), StandardCharsets.US_ASCII))
        readReply("connect greeting")
    }

    override fun authenticate(cookie: ByteArray) {
        val hex = cookie.joinToString("") { "%02x".format(it) }
        sendCommand("AUTHENTICATE $hex")
    }

    override fun bootstrapProgress(): Int? =
        Regex("PROGRESS=(\\d+)")
            .find(sendCommand("GETINFO status/bootstrap-phase"))
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()

    override fun addOnionService(
        seed: ByteArray,
        virtualPort: Int,
        targetHost: String,
        targetPort: Int,
    ): String {
        val key = Base64.getEncoder().encodeToString(seed)
        val reply = sendCommand("ADD_ONION ED25519-V3:$key Port=$virtualPort,$targetHost:$targetPort")
        val serviceId =
            Regex("ServiceID=([a-z2-7]{56})").find(reply)?.groupValues?.get(1)
                ?: error("ADD_ONION returned no ServiceID: $reply")
        return "$serviceId.onion"
    }

    override fun deleteOnionService(address: String) {
        sendCommand("DEL_ONION ${address.removeSuffix(".onion")}")
    }

    override fun close() {
        runCatching {
            writer?.write("QUIT\n")
            writer?.flush()
        }
        runCatching { socket?.close() }
        socket = null
        reader = null
        writer = null
    }

    private fun sendCommand(command: String): String {
        val w = checkNotNull(writer) { "TorControl not connected" }
        w.write(command + "\n")
        w.flush()
        return readReply(command)
    }

    /** Consumes reply lines until a final "NNN ..." line; throws on 5xx. */
    private fun readReply(command: String): String {
        val r = checkNotNull(reader) { "TorControl not connected" }
        val lines = mutableListOf<String>()
        var done = false
        while (!done) {
            val line = r.readLine() ?: error("Tor closed the connection during: $command")
            if (line.startsWith("650 ")) continue
            lines += line
            val isFinal = line.length <= 3 || line[3] == ' '
            if (isFinal) done = true
        }
        if (lines.firstOrNull()?.startsWith("5") == true) {
            error("Tor rejected '$command': ${lines.joinToString("\n")}")
        }
        return lines.joinToString("\n")
    }
}
