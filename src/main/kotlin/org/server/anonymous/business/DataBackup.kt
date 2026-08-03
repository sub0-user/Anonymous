package org.server.anonymous.business

import org.server.anonymous.business.model.Contact
import org.server.anonymous.business.model.MemberStatus
import org.server.anonymous.business.model.RoomMember
import org.server.anonymous.business.model.RoomRecord
import org.server.anonymous.business.model.RoomType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.spec.KeySpec
import java.util.Base64
import java.util.Properties
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Phase B1: the passphrase-protected backup is no longer just the seed. A v2 bundle wraps
 * the identity, the contact list (with safety-number bindings), the block list and the room
 * records (room keys, client-auth keys, entry keys) in one AES-GCM envelope — the same
 * PBKDF2 + AES-GCM parameters as IdentityBackup. Restoring a v2 backup brings back the
 * whole profile; an old v1 seed-only backup still imports (contacts/rooms stay empty).
 *
 * @Suppress TooManyFunctions, ReturnCount: one serialization surface over a fixed schema;
 * the early returns are the per-field validation guards.
 */
@Suppress("TooManyFunctions", "ReturnCount")
object DataBackup {
    private const val HEADER = "anonymous-data-v1"
    private const val LEGACY_HEADER = "anonymous-identity-v1"
    private const val ITERATIONS = 200_000
    private const val SALT_LENGTH = 16
    private const val IV_LENGTH = 12
    private const val VERSION = "2"

    data class Contents(
        val seed: ByteArray,
        val contacts: List<Contact>,
        val blocked: List<String>,
        val rooms: List<RoomRecord>,
    )

    /** Returns the backup file contents (UTF-8 properties). */
    fun export(
        seed: ByteArray,
        contacts: List<Contact>,
        blocked: List<String>,
        rooms: List<RoomRecord>,
        passphrase: CharArray,
    ): ByteArray {
        val plaintext = "$HEADER\n".toByteArray(Charsets.UTF_8) + bundle(seed, contacts, blocked, rooms)
        return encrypt(plaintext, passphrase)
    }

    /** Returns the restored contents, or throws on a wrong passphrase / tampered data. */
    fun import(
        data: ByteArray,
        passphrase: CharArray,
    ): Contents {
        val plaintext = decrypt(data, passphrase)
        val text = plaintext.toString(Charsets.UTF_8)
        if (text.startsWith(LEGACY_HEADER)) {
            // v1 seed-only backup — the whole payload after the header is the seed.
            val seed = plaintext.copyOfRange(LEGACY_HEADER.length + 1, plaintext.size)
            return Contents(seed, emptyList(), emptyList(), emptyList())
        }
        check(text.startsWith(HEADER)) { "backup is not a valid Anonymous backup" }
        val props =
            Properties().apply {
                ByteArrayInputStream(text.substringAfter('\n').toByteArray(Charsets.UTF_8)).use { load(it) }
            }
        return Contents(
            seed = decode(props.getProperty("identity.seed") ?: invalid()),
            contacts = readContacts(props),
            blocked = readBlocked(props),
            rooms = readRooms(props),
        )
    }

    private fun bundle(
        seed: ByteArray,
        contacts: List<Contact>,
        blocked: List<String>,
        rooms: List<RoomRecord>,
    ): ByteArray {
        val props = Properties()
        props.setProperty("identity.seed", encode(seed))
        props.setProperty("contact.count", contacts.size.toString())
        contacts.forEachIndexed { i, contact ->
            val p = "contact.$i."
            props.setProperty("${p}id", contact.id.toString())
            props.setProperty("${p}alias", contact.alias)
            props.setProperty("${p}address", contact.address.value)
            contact.peerPublicKey?.let { props.setProperty("${p}peerKey", encode(it)) }
        }
        props.setProperty("blocked.count", blocked.size.toString())
        blocked.forEachIndexed { i, address -> props.setProperty("blocked.$i", address) }
        props.setProperty("room.count", rooms.size.toString())
        rooms.forEachIndexed { i, room -> writeRoom(props, "room.$i.", room) }
        return ByteArrayOutputStream().use { bytes ->
            props.store(bytes, "anonymous backup")
            bytes.toByteArray()
        }
    }

    private fun writeRoom(
        props: Properties,
        p: String,
        room: RoomRecord,
    ) {
        props.setProperty("${p}id", room.id.toString())
        props.setProperty("${p}name", room.name)
        props.setProperty("${p}type", room.type.name)
        props.setProperty("${p}isFounder", room.isFounder.toString())
        room.founderAddress?.let { props.setProperty("${p}founderAddress", it) }
        room.founderPublicKey?.let { props.setProperty("${p}founderPublicKey", encode(it)) }
        props.setProperty("${p}serviceSeed", encode(room.serviceSeed))
        props.setProperty("${p}serviceAddress", room.serviceAddress)
        props.setProperty("${p}roomKey", encode(room.roomKey))
        props.setProperty("${p}keyVersion", room.keyVersion.toString())
        room.entryKey?.let { props.setProperty("${p}entryKey", it) }
        props.setProperty("${p}myName", room.myName)
        props.setProperty("${p}member.count", room.members.size.toString())
        room.members.forEachIndexed { i, member ->
            val m = "$p" + "member.$i."
            props.setProperty("${m}publicKey", encode(member.publicKey))
            props.setProperty("${m}name", member.name)
            props.setProperty("${m}status", member.status.name)
            member.wrappedRoomKey?.let { props.setProperty("${m}wrappedRoomKey", encode(it)) }
            member.address?.let { props.setProperty("${m}address", it) }
            member.inviteExpiryEpochSeconds?.let { props.setProperty("${m}inviteExpiry", it.toString()) }
        }
    }

    private fun readContacts(props: Properties): List<Contact> {
        val count = props.getProperty("contact.count")?.toIntOrNull() ?: return emptyList()
        return (0 until count).mapNotNull { i ->
            val p = "contact.$i."
            val id = props.getProperty("${p}id")?.toLongOrNull() ?: return@mapNotNull null
            val alias = props.getProperty("${p}alias") ?: return@mapNotNull null
            val address = props.getProperty("${p}address") ?: return@mapNotNull null
            val peerKey = props.getProperty("${p}peerKey")?.let { runCatching { decode(it) }.getOrNull() }
            Contact(id, alias, OnionAddress(address), peerPublicKey = peerKey)
        }
    }

    private fun readBlocked(props: Properties): List<String> {
        val count = props.getProperty("blocked.count")?.toIntOrNull() ?: return emptyList()
        return (0 until count).mapNotNull { i -> props.getProperty("blocked.$i") }
    }

    private fun readRooms(props: Properties): List<RoomRecord> {
        val count = props.getProperty("room.count")?.toIntOrNull() ?: return emptyList()
        return (0 until count).mapNotNull { i -> readRoom(props, "room.$i.") }
    }

    private fun readRoom(
        props: Properties,
        p: String,
    ): RoomRecord? {
        val id = props.getProperty("${p}id")?.toLongOrNull() ?: return null
        val name = props.getProperty("${p}name") ?: return null
        val typeValue = props.getProperty("${p}type")
        val type = if (typeValue == null) null else runCatching { RoomType.valueOf(typeValue) }.getOrNull()
        if (type == null) return null
        val isFounder = props.getProperty("${p}isFounder")?.toBoolean() ?: return null
        val serviceSeed = b64OrNull(props, "${p}serviceSeed") ?: return null
        val serviceAddress = props.getProperty("${p}serviceAddress") ?: return null
        val roomKey = b64OrNull(props, "${p}roomKey") ?: return null
        val keyVersion = props.getProperty("${p}keyVersion")?.toIntOrNull() ?: return null
        val myName = props.getProperty("${p}myName") ?: return null
        val members =
            readMembers(props, p + "member.")
        return RoomRecord(
            id = id,
            name = name,
            type = type,
            isFounder = isFounder,
            founderAddress = props.getProperty("${p}founderAddress"),
            founderPublicKey = b64OrNull(props, "${p}founderPublicKey"),
            serviceSeed = serviceSeed,
            serviceAddress = serviceAddress,
            roomKey = roomKey,
            keyVersion = keyVersion,
            entryKey = props.getProperty("${p}entryKey"),
            myName = myName,
            members = members,
        )
    }

    private fun readMembers(
        props: Properties,
        p: String,
    ): List<RoomMember> {
        val count = props.getProperty("${p}count")?.toIntOrNull() ?: return emptyList()
        return (0 until count).mapNotNull { i ->
            val m = "$p$i."
            val publicKey = b64OrNull(props, "${m}publicKey") ?: return@mapNotNull null
            val name = props.getProperty("${m}name") ?: return@mapNotNull null
            val statusValue = props.getProperty("${m}status")
            val status =
                if (statusValue == null) {
                    MemberStatus.MEMBER
                } else {
                    runCatching { MemberStatus.valueOf(statusValue) }.getOrNull() ?: MemberStatus.MEMBER
                }
            RoomMember(
                publicKey = publicKey,
                name = name,
                status = status,
                wrappedRoomKey = b64OrNull(props, "${m}wrappedRoomKey"),
                address = props.getProperty("${m}address"),
                inviteExpiryEpochSeconds = props.getProperty("${m}inviteExpiry")?.toLongOrNull(),
            )
        }
    }

    private fun encrypt(
        plaintext: ByteArray,
        passphrase: CharArray,
    ): ByteArray {
        val salt = SessionCrypto.randomBytes(SALT_LENGTH)
        val iv = SessionCrypto.randomBytes(IV_LENGTH)
        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return buildProperties(salt, iv, cipher.doFinal(plaintext)).toByteArray(Charsets.UTF_8)
    }

    private fun decrypt(
        data: ByteArray,
        passphrase: CharArray,
    ): ByteArray {
        val props =
            Properties().apply {
                ByteArrayInputStream(data).use { load(it) }
            }
        val salt = decode(props.getProperty("salt") ?: invalid())
        val iv = decode(props.getProperty("iv") ?: invalid())
        val ciphertext = decode(props.getProperty("ciphertext") ?: invalid())
        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext) // AEADBadTagException on wrong passphrase/tamper
    }

    private fun invalid(): Nothing = error("backup is not a valid Anonymous backup")

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decode(value: String): ByteArray = Base64.getDecoder().decode(value)

    /** Reads a base64 property, or null when absent or corrupt (that field is skipped). */
    private fun b64OrNull(
        props: Properties,
        key: String,
    ): ByteArray? = props.getProperty(key)?.let { runCatching { decode(it) }.getOrNull() }

    private fun deriveKey(
        passphrase: CharArray,
        salt: ByteArray,
    ): ByteArray {
        val spec: KeySpec = PBEKeySpec(passphrase, salt, ITERATIONS, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun buildProperties(
        salt: ByteArray,
        iv: ByteArray,
        ciphertext: ByteArray,
    ): String {
        val encoder = Base64.getEncoder()
        return buildString {
            appendLine("anonymous.backup.version=$VERSION")
            appendLine("kdf=pbkdf2-hmac-sha256")
            appendLine("kdf.iterations=$ITERATIONS")
            appendLine("salt=${encoder.encodeToString(salt)}")
            appendLine("iv=${encoder.encodeToString(iv)}")
            append("ciphertext=${encoder.encodeToString(ciphertext)}")
        }
    }
}
