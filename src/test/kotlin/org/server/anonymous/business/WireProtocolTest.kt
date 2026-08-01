package org.server.anonymous.business

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class WireProtocolTest {
    private fun roundTrip(
        type: Int,
        payload: ByteArray,
    ): WireProtocol.Frame {
        val bytes = ByteArrayOutputStream()
        WireProtocol.writeFrame(DataOutputStream(bytes), type, payload)
        return WireProtocol.readFrame(DataInputStream(ByteArrayInputStream(bytes.toByteArray())))
    }

    @Test
    fun `frame roundtrip preserves type and payload`() {
        val frame = roundTrip(WireProtocol.TYPE_DATA, byteArrayOf(1, 2, 3, 4))
        assertEquals(WireProtocol.TYPE_DATA, frame.type)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), frame.payload)
    }

    @Test
    fun `empty payload frame roundtrips`() {
        val frame = roundTrip(WireProtocol.TYPE_ACK, ByteArray(0))
        assertEquals(WireProtocol.TYPE_ACK, frame.type)
        assertEquals(0, frame.payload.size)
    }

    @Test
    fun `maximum-size payload roundtrips`() {
        val payload = ByteArray(WireProtocol.MAX_FRAME_SIZE - 1)
        val frame = roundTrip(WireProtocol.TYPE_DATA, payload)
        assertEquals(WireProtocol.MAX_FRAME_SIZE - 1, frame.payload.size)
    }

    @Test
    fun `oversized payload is rejected when writing`() {
        val out = DataOutputStream(ByteArrayOutputStream())
        assertThrows(IllegalStateException::class.java) {
            WireProtocol.writeFrame(out, WireProtocol.TYPE_DATA, ByteArray(WireProtocol.MAX_FRAME_SIZE))
        }
    }

    @Test
    fun `zero length frame is rejected`() {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).writeInt(0)
        assertThrows(IllegalStateException::class.java) {
            WireProtocol.readFrame(DataInputStream(ByteArrayInputStream(bytes.toByteArray())))
        }
    }

    @Test
    fun `oversized frame is rejected when reading`() {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).writeInt(WireProtocol.MAX_FRAME_SIZE + 1)
        assertThrows(IllegalStateException::class.java) {
            WireProtocol.readFrame(DataInputStream(ByteArrayInputStream(bytes.toByteArray())))
        }
    }

    @Test
    fun `truncated frame is rejected`() {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        out.writeInt(100)
        out.write(byteArrayOf(1, 2, 3)) // fewer than promised
        assertThrows(java.io.EOFException::class.java) {
            WireProtocol.readFrame(DataInputStream(ByteArrayInputStream(bytes.toByteArray())))
        }
    }
}
