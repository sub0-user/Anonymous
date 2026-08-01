package org.server.anonymous.business

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.Base64

class ClientAuthBlobTest {
    @Test
    fun `keypair has 32 byte halves`() {
        val pair = ClientAuthBlob.createKeyPair()
        assertEquals(32, pair.privateScalar.size)
        assertEquals(32, pair.publicU.size)
    }

    @Test
    fun `tor add-onion blob is base64 of public plus private`() {
        val pair = ClientAuthBlob.createKeyPair()
        val decoded = Base64.getDecoder().decode(ClientAuthBlob.torAddOnionBlob(pair))
        assertEquals(64, decoded.size)
        assertArrayEquals(pair.publicU, decoded.copyOfRange(0, 32))
        assertArrayEquals(pair.privateScalar, decoded.copyOfRange(32, 64))
    }

    @Test
    fun `auth private file roundtrips through parse`() {
        val pair = ClientAuthBlob.createKeyPair()
        val content = ClientAuthBlob.authPrivateFileContent(pair)
        assertArrayEquals(pair.privateScalar, ClientAuthBlob.parseAuthPrivateFile(content))
    }

    @Test
    fun `two generated pairs differ`() {
        val a = ClientAuthBlob.createKeyPair()
        val b = ClientAuthBlob.createKeyPair()
        assertFalse(a.privateScalar.contentEquals(b.privateScalar))
        assertFalse(a.publicU.contentEquals(b.publicU))
    }

    @Test
    fun `parse rejects a wrong sized key`() {
        val short = Base64.getEncoder().encodeToString(ByteArray(31))
        assertThrows(IllegalStateException::class.java) {
            ClientAuthBlob.parseAuthPrivateFile("x25519:$short")
        }
    }
}
