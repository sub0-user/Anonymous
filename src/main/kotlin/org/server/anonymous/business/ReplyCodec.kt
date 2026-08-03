package org.server.anonymous.business

import org.server.anonymous.business.model.ReplyRef
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Wire codec for message payloads with an optional reply reference. Plain messages keep
 * the exact legacy payload (raw UTF-8 body) so old peers and stored history stay
 * readable; a reply is prefixed with a magic tag and length-prefixed reply fields:
 * `"anr1" | u16 nameLen | name | u16 keyLen | key | u16 textLen | text | body`.
 * Anything that does not start with the magic is treated as a plain body.
 */
object ReplyCodec {
    private const val MAGIC = "anr1"
    private const val MAX_KEY_LENGTH = 32

    /** Encodes a message payload; a null [replyTo] yields the legacy plain-body form. */
    fun encode(
        body: String,
        replyTo: ReplyRef?,
    ): ByteArray {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        if (replyTo == null) return bodyBytes
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeBytes(MAGIC)
                writeField(out, replyTo.senderName)
                val key = replyTo.senderKey ?: ByteArray(0)
                check(key.size <= MAX_KEY_LENGTH) { "reply key too long" }
                out.writeShort(key.size)
                out.write(key)
                writeField(out, replyTo.text)
                out.write(bodyBytes)
            }
            bytes.toByteArray()
        }
    }

    private val magicBytes = MAGIC.toByteArray(Charsets.US_ASCII)

    /** Decodes a payload into its body text and optional reply reference. */
    fun decode(payload: ByteArray): Pair<String, ReplyRef?> {
        if (payload.size < magicBytes.size || !payload.copyOfRange(0, magicBytes.size).contentEquals(magicBytes)) {
            return payload.toString(Charsets.UTF_8) to null
        }
        DataInputStream(ByteArrayInputStream(payload.copyOfRange(MAGIC.length, payload.size))).use { input ->
            val senderName = readField(input)
            val keyLength = input.readUnsignedShort()
            check(keyLength <= MAX_KEY_LENGTH)
            val key = ByteArray(keyLength).also { input.readFully(it) }
            val text = readField(input)
            val body = input.readBytes().toString(Charsets.UTF_8)
            return body to ReplyRef(if (key.isEmpty()) null else key, senderName, text)
        }
    }

    private fun writeField(
        out: DataOutputStream,
        value: String?,
    ) {
        val bytes = (value ?: "").toByteArray(Charsets.UTF_8)
        check(bytes.size <= 0xFFFF) { "reply field too long" }
        out.writeShort(bytes.size)
        out.write(bytes)
    }

    private fun readField(input: DataInputStream): String {
        val length = input.readUnsignedShort()
        return String(ByteArray(length).also { input.readFully(it) }, Charsets.UTF_8)
    }
}
