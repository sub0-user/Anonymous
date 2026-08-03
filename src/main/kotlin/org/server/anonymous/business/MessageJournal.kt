package org.server.anonymous.business

import org.server.anonymous.business.model.MessageItem
import org.server.anonymous.business.model.ReplyRef
import org.server.anonymous.business.model.RoomMessageItem
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Append-only, encrypted-at-rest message history (Phase A1). One file per conversation
 * (contact or room); each record is `[len:4][nonce:12][ciphertext]` where the length is
 * OUTSIDE the AEAD envelope so a corrupt or truncated tail never hides earlier records —
 * loading skips whatever fails and keeps the rest, the same tolerance as RoomStore.
 *
 * The key is derived from the identity seed (HKDF, info = file name) and the file name is
 * the AEAD additional data, so a record can never be replayed into another conversation's
 * file. History is bound to the identity: restoring a backup with a different seed yields
 * an empty (undecryptable) history, never garbage.
 *
 * @Suppress TooManyFunctions: a small fixed surface over one file (load/append/clear plus
 * the private record helpers); splitting them across files would scatter the format.
 */
@Suppress("TooManyFunctions")
class MessageJournal<T>(
    private val file: Path,
    private val identity: () -> Identity,
    private val encode: (Long, T) -> ByteArray,
    private val decode: (ByteArray) -> Pair<Long, T>?,
) {
    /** Decrypts and decodes every valid record in file order; undecryptable records are skipped. */
    @Suppress("ReturnCount") // history is best-effort: each early return is a missing or corrupt input
    fun load(): List<Pair<Long, T>> {
        val raw =
            if (Files.exists(file)) {
                runCatching { Files.readAllBytes(file) }.getOrNull()
            } else {
                null
            }
        if (raw == null) return emptyList()
        val input = DataInputStream(ByteArrayInputStream(raw))
        if (!readMagic(input)) return emptyList()
        val key = historyKey()
        val aad = file.fileName.toString().toByteArray(Charsets.UTF_8)
        val out = mutableListOf<Pair<Long, T>>()
        while (input.available() >= 4) {
            val record = readRecord(input) ?: break // corrupt/truncated tail — the earlier records stand
            val decoded = decryptRecord(key, aad, record)
            if (decoded != null) out += decoded
        }
        return out
    }

    /** Reads one length-prefixed record; null means the tail is corrupt or truncated. */
    private fun readRecord(input: DataInputStream): ByteArray? {
        val length = input.readInt()
        val valid =
            length >= SessionCrypto.NONCE_LENGTH &&
                length <= MAX_RECORD_LENGTH &&
                input.available() >= length
        return if (valid) ByteArray(length).also { input.readFully(it) } else null
    }

    /** Decrypts and decodes one record; null means it failed (wrong key, tamper) and is skipped. */
    private fun decryptRecord(
        key: ByteArray,
        aad: ByteArray,
        record: ByteArray,
    ): Pair<Long, T>? {
        val decoded =
            runCatching {
                val nonce = record.copyOfRange(0, SessionCrypto.NONCE_LENGTH)
                val ciphertext = record.copyOfRange(SessionCrypto.NONCE_LENGTH, record.size)
                val plaintext = SessionCrypto.decrypt(key, nonce, ciphertext, aad)
                decode(plaintext)
            }.getOrNull()
        return if (decoded != null) decoded else null
    }

    /** Appends one record; a crash mid-write leaves a truncated tail that [load] ignores. */
    @Synchronized
    fun append(
        conversationId: Long,
        item: T,
    ) {
        Files.createDirectories(file.parent)
        PrivateFileOps.setPrivateDir(file.parent)
        val key = historyKey()
        val aad = file.fileName.toString().toByteArray(Charsets.UTF_8)
        openForAppend(file).use { channel ->
            if (channel.size() == 0L) channel.write(ByteBuffer.wrap(MAGIC))
            writeRecord(channel, key, aad, conversationId, item)
        }
        PrivateFileOps.setPrivateFile(file)
    }

    /** Removes one conversation's records by rewriting the file with the rest kept intact. */
    @Synchronized
    fun clear(conversationId: Long) {
        if (!Files.exists(file)) return
        val kept = load().filter { it.first != conversationId }
        rewrite(kept)
    }

    fun clearAll() {
        Files.deleteIfExists(file)
    }

    /** Rewrites the whole file atomically (temp file + move) so a crash never leaves it half-written. */
    @Synchronized
    private fun rewrite(records: List<Pair<Long, T>>) {
        if (records.isEmpty()) {
            Files.deleteIfExists(file)
            return
        }
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.deleteIfExists(tmp)
        try {
            appendAll(tmp, records)
            PrivateFileOps.setPrivateFile(tmp)
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    /** Appends [records] to [target] using a fresh key bound to the ORIGINAL file's name. */
    private fun appendAll(
        target: Path,
        records: List<Pair<Long, T>>,
    ) {
        Files.createDirectories(target.parent)
        val key = historyKey()
        val aad = file.fileName.toString().toByteArray(Charsets.UTF_8)
        openForAppend(target).use { channel ->
            if (channel.size() == 0L) channel.write(ByteBuffer.wrap(MAGIC))
            for ((conversationId, item) in records) {
                writeRecord(channel, key, aad, conversationId, item)
            }
        }
    }

    /** Encrypts and writes one length-prefixed record to an open channel. */
    private fun writeRecord(
        channel: FileChannel,
        key: ByteArray,
        aad: ByteArray,
        conversationId: Long,
        item: T,
    ) {
        val plaintext = encode(conversationId, item)
        val nonce = SessionCrypto.randomNonce()
        val ciphertext = SessionCrypto.encrypt(key, nonce, plaintext, aad)
        val record =
            ByteArrayOutputStream(4 + nonce.size + ciphertext.size).use { bytes ->
                DataOutputStream(bytes).use { out ->
                    out.writeInt(nonce.size + ciphertext.size)
                    out.write(nonce)
                    out.write(ciphertext)
                }
                bytes.toByteArray()
            }
        channel.write(ByteBuffer.wrap(record))
    }

    /** The per-file key: HKDF(identity seed, salt, info = file name) — 32 bytes for ChaCha20. */
    private fun historyKey(): ByteArray =
        SessionCrypto.hkdf(
            identity().seed,
            "anonymous/history/v1".toByteArray(Charsets.UTF_8),
            file.fileName.toString().toByteArray(Charsets.UTF_8),
            SessionCrypto.KEY_LENGTH,
        )

    /** Open-or-create a history file for appending (chmod happens on the first write). */
    private fun openForAppend(path: Path): FileChannel =
        FileChannel.open(
            path,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
            StandardOpenOption.WRITE,
        )

    private fun readMagic(input: DataInputStream): Boolean {
        val magic = ByteArray(MAGIC.size)
        if (input.available() < magic.size) return false
        input.readFully(magic)
        return magic.contentEquals(MAGIC)
    }

    private companion object {
        val MAGIC = "ANONHIST2".toByteArray(Charsets.US_ASCII)
        const val MAX_RECORD_LENGTH = 512 * 1024
    }
}

/** Binary serializers shared by the 1:1 and room journals (hand-rolled, no third-party libs). */
object HistoryCodec {
    /** Serializes a 1:1 message: contactId + MessageItem fields. */
    fun encodeMessageItem(
        contactId: Long,
        item: MessageItem,
    ): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeLong(contactId)
                out.writeLong(item.id)
                out.writeByte(item.direction.ordinal)
                out.writeByte(item.status.ordinal)
                out.writeUTF(item.sentAtLabel)
                out.writeBoolean(item.replyTo != null)
                item.replyTo?.let { reply ->
                    writeReplyField(out, reply.senderName)
                    writeReplyField(out, reply.senderKey)
                    writeReplyField(out, reply.text)
                }
                out.writeInt(item.body.toByteArray(Charsets.UTF_8).size)
                out.write(item.body.toByteArray(Charsets.UTF_8))
            }
            bytes.toByteArray()
        }

    /** Deserializes a 1:1 message; null when the record is malformed (and so skipped). */
    fun decodeMessageItem(payload: ByteArray): Pair<Long, MessageItem>? =
        runCatching {
            DataInputStream(ByteArrayInputStream(payload)).use { input ->
                val contactId = input.readLong()
                val id = input.readLong()
                val direction = org.server.anonymous.business.model.MessageDirection.entries[input.readByte().toInt()]
                val status = org.server.anonymous.business.model.MessageStatus.entries[input.readByte().toInt()]
                val sentAtLabel = input.readUTF()
                val replyTo =
                    if (input.readBoolean()) {
                        val name = readReplyField(input)
                        val key = readReplyBytes(input)
                        val text = readReplyField(input)
                        val senderKey = if (key.isEmpty()) null else key
                        ReplyRef(senderKey, name, text)
                    } else {
                        null
                    }
                val bodyLength = input.readInt()
                check(bodyLength >= 0 && bodyLength <= 64 * 1024)
                val bodyBytes = ByteArray(bodyLength)
                input.readFully(bodyBytes)
                val body = String(bodyBytes, Charsets.UTF_8)
                contactId to MessageItem(id, direction, body, status, sentAtLabel, replyTo)
            }
        }.getOrNull()

    /** Serializes a room message: roomId + RoomMessageItem fields. */
    fun encodeRoomItem(
        roomId: Long,
        item: RoomMessageItem,
    ): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeLong(roomId)
                out.writeLong(item.id)
                out.writeBoolean(item.isOutgoing)
                out.writeByte(item.senderPublicKey.size)
                out.write(item.senderPublicKey)
                out.writeUTF(item.timeLabel)
                out.writeBoolean(item.replyTo != null)
                item.replyTo?.let { reply ->
                    writeReplyField(out, reply.senderName)
                    writeReplyField(out, reply.senderKey)
                    writeReplyField(out, reply.text)
                }
                out.writeInt(item.body.toByteArray(Charsets.UTF_8).size)
                out.write(item.body.toByteArray(Charsets.UTF_8))
            }
            bytes.toByteArray()
        }

    /** Writes a reply field as u16 length + bytes (empty when null). */
    private fun writeReplyField(
        out: DataOutputStream,
        value: ByteArray?,
    ) {
        val bytes = value ?: ByteArray(0)
        check(bytes.size <= 0xFFFF) { "reply field too long" }
        out.writeShort(bytes.size)
        out.write(bytes)
    }

    private fun writeReplyField(
        out: DataOutputStream,
        value: String?,
    ) = writeReplyField(out, (value ?: "").toByteArray(Charsets.UTF_8))

    private fun readReplyField(input: DataInputStream): String = String(readReplyBytes(input), Charsets.UTF_8)

    private fun readReplyBytes(input: DataInputStream): ByteArray {
        val length = input.readUnsignedShort()
        return ByteArray(length).also { input.readFully(it) }
    }

    /** Deserializes a room message; null when the record is malformed (and so skipped). */
    fun decodeRoomItem(payload: ByteArray): Pair<Long, RoomMessageItem>? =
        runCatching {
            DataInputStream(ByteArrayInputStream(payload)).use { input ->
                val roomId = input.readLong()
                val id = input.readLong()
                val isOutgoing = input.readBoolean()
                val keyLength = input.readByte().toInt()
                check(keyLength in 1..64)
                val key = ByteArray(keyLength).also { input.readFully(it) }
                val timeLabel = input.readUTF()
                val replyTo =
                    if (input.readBoolean()) {
                        val name = readReplyField(input)
                        val replyKey = readReplyBytes(input)
                        val text = readReplyField(input)
                        val senderKey = if (replyKey.isEmpty()) null else replyKey
                        ReplyRef(senderKey, name, text)
                    } else {
                        null
                    }
                val bodyLength = input.readInt()
                check(bodyLength >= 0 && bodyLength <= 64 * 1024)
                val bodyBytes = ByteArray(bodyLength)
                input.readFully(bodyBytes)
                val body = String(bodyBytes, Charsets.UTF_8)
                val message =
                    RoomMessageItem(id, roomId, key, body, timeLabel, isOutgoing, replyTo)
                roomId to message
            }
        }.getOrNull()
}
