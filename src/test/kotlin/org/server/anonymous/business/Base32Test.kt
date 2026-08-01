package org.server.anonymous.business

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class Base32Test {
    /** RFC 4648 §10 test vectors (unpadded). */
    @Test
    fun `matches the RFC 4648 test vectors`() {
        assertEquals("", Base32.encode(ByteArray(0)))
        assertEquals("MY", Base32.encode("f".toByteArray()))
        assertEquals("MZXQ", Base32.encode("fo".toByteArray()))
        assertEquals("MZXW6", Base32.encode("foo".toByteArray()))
        assertEquals("MZXW6YQ", Base32.encode("foob".toByteArray()))
        assertEquals("MZXW6YTB", Base32.encode("fooba".toByteArray()))
        assertEquals("MZXW6YTBOI", Base32.encode("foobar".toByteArray()))
    }

    @Test
    fun `encode and decode roundtrip`() {
        repeat(50) { size ->
            val bytes = ByteArray(size) { (it * 7 + size).toByte() }
            assertArrayEquals(bytes, Base32.decode(Base32.encode(bytes)))
        }
    }

    @Test
    fun `decode accepts lowercase`() {
        assertArrayEquals("foobar".toByteArray(), Base32.decode("mzxw6ytboi"))
    }

    @Test
    fun `decode rejects non alphabet characters`() {
        assertThrows(IllegalStateException::class.java) {
            Base32.decode("MZXW6YTBO1") // digit '1' is not in the alphabet
        }
    }

    @Test
    fun `decode rejects nonzero padding bits`() {
        // 1 byte = 2 chars; the trailing char's low 2 bits are padding. 'B' (value 1)
        // leaves nonzero padding, 'M' (value 12) is clean padding.
        assertEquals(0x66, Base32.decode("MY")[0].toInt() and 0xFF)
        assertThrows(IllegalStateException::class.java) {
            Base32.decode("MB")
        }
    }

    @Test
    fun `decode rejects padding characters`() {
        assertThrows(IllegalStateException::class.java) {
            Base32.decode("MY=")
        }
    }
}
