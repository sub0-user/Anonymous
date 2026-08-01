package org.server.anonymous.business

import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Length-prefixed frames + envelope types for the P2P wire protocol.
 * Frame = 4-byte big-endian length, then type byte, then payload. Everything inbound
 * is validated; malformed or oversized frames raise and the connection is dropped.
 */
object WireProtocol {
    const val MAX_FRAME_SIZE = 64 * 1024
    const val PROTOCOL_VERSION = 1

    const val TYPE_HELLO = 1
    const val TYPE_DATA = 2
    const val TYPE_ACK = 3

    const val CONTENT_TEXT = 1
    const val CONTENT_ROOM_MSG = 2
    const val CONTENT_ROOM_CONTROL = 3

    const val SESSION_NONCE_LENGTH = 32
    const val MAX_ADDRESS_LENGTH = 64

    /** AAD binding every encrypted frame to this protocol (tampering with the type fails the tag). */
    const val AAD = "anonymous:data:v1"

    data class Frame(
        val type: Int,
        val payload: ByteArray,
    )

    fun writeFrame(
        output: DataOutputStream,
        type: Int,
        payload: ByteArray = ByteArray(0),
    ) {
        check(payload.size <= MAX_FRAME_SIZE - 1) { "frame too large: ${payload.size}" }
        output.writeInt(payload.size + 1)
        output.writeByte(type)
        output.write(payload)
        output.flush()
    }

    fun readFrame(input: DataInputStream): Frame {
        val length = input.readInt()
        if (length < 1 || length > MAX_FRAME_SIZE) error("malformed frame length: $length")
        val data = ByteArray(length)
        input.readFully(data)
        return Frame(data[0].toInt() and 0xFF, data.copyOfRange(1, data.size))
    }
}
