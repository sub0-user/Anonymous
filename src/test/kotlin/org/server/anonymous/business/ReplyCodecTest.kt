package org.server.anonymous.business

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.server.anonymous.business.model.ReplyRef

class ReplyCodecTest {
    @Test
    fun `plain messages keep the legacy payload`() {
        val encoded = ReplyCodec.encode("hello world", null)
        assertArrayEquals("hello world".toByteArray(Charsets.UTF_8), encoded)
        val (text, reply) = ReplyCodec.decode(encoded)
        assertEquals("hello world", text)
        assertNull(reply)
    }

    @Test
    fun `reply round-trips with name key and preview`() {
        val ref = ReplyRef(ByteArray(32) { 7 }, "raven", "the original")
        val encoded = ReplyCodec.encode("got it", ref)
        val (text, reply) = ReplyCodec.decode(encoded)
        assertEquals("got it", text)
        assertArrayEquals(ByteArray(32) { 7 }, reply!!.senderKey)
        assertEquals("raven", reply.senderName)
        assertEquals("the original", reply.text)
    }

    @Test
    fun `reply with no sender key round-trips as null`() {
        val encoded = ReplyCodec.encode("hi", ReplyRef(senderName = null, text = "x"))
        val (text, reply) = ReplyCodec.decode(encoded)
        assertEquals("hi", text)
        assertNull(reply!!.senderKey)
        assertEquals("x", reply.text)
    }

    @Test
    fun `preview is flattened and bounded`() {
        val long = "a".repeat(500)
        val preview = ReplyRef.previewOf("line1\nline2   $long")
        assertTrue(!preview.contains("\n"))
        assertTrue(preview.length <= ReplyRef.MAX_TEXT_LENGTH)
        assertEquals("line1 line2 " + "a".repeat(200 - "line1 line2 ".length), preview)
    }

    @Test
    fun `garbage without the magic decodes as plain text`() {
        val (text, reply) = ReplyCodec.decode("anr".toByteArray(Charsets.UTF_8))
        assertEquals("anr", text)
        assertNull(reply)
    }
}
