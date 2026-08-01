package org.server.anonymous.business

import org.server.anonymous.business.model.MemberStatus
import org.server.anonymous.business.model.RoomMember
import org.server.anonymous.business.model.RoomRecord
import org.server.anonymous.business.model.RoomType
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.Properties

/**
 * Persists [RoomRecord]s as one 0600 properties file per room under the data directory,
 * same pattern as `identity.properties`. Corrupt files are skipped on load, never fatal.
 *
 * @Suppress TooManyFunctions: one store with a fixed encode/decode surface; splitting it
 * would hide the file format in fragments.
 */
@Suppress("TooManyFunctions")
class RoomStore(
    private val roomDir: Path,
) {
    fun loadAll(): List<RoomRecord> {
        if (!Files.exists(roomDir)) return emptyList()
        val files = Files.list(roomDir).use { stream -> stream.toList() }
        return files
            .filter { it.fileName.toString().endsWith(SUFFIX) }
            .sorted()
            .mapNotNull { runCatching { load(it) }.getOrNull() }
    }

    fun save(record: RoomRecord) {
        Files.createDirectories(roomDir)
        PrivateFileOps.setPrivateDir(roomDir)
        val props =
            Properties().apply {
                setProperty("id", "%016x".format(record.id))
                setProperty("name", record.name)
                setProperty("type", record.type.name)
                setProperty("isFounder", record.isFounder.toString())
                record.founderAddress?.let { setProperty("founderAddress", it) }
                record.founderPublicKey?.let { setProperty("founderPublicKey", b64(it)) }
                setProperty("serviceSeed", b64(record.serviceSeed))
                setProperty("serviceAddress", record.serviceAddress)
                setProperty("roomKey", b64(record.roomKey))
                setProperty("keyVersion", record.keyVersion.toString())
                record.entryKey?.let { setProperty("entryKey", it) }
                setProperty("myName", record.myName)
                record.members.forEachIndexed { index, member ->
                    setProperty("member.$index.pub", b64(member.publicKey))
                    setProperty("member.$index.name", member.name)
                    setProperty("member.$index.status", member.status.name)
                    member.clientAuthPrivate?.let { setProperty("member.$index.authPriv", b64(it)) }
                    member.wrappedRoomKey?.let { setProperty("member.$index.wrappedKey", b64(it)) }
                    member.address?.let { setProperty("member.$index.address", it) }
                    member.inviteExpiryEpochSeconds?.let {
                        setProperty("member.$index.inviteExpiry", it.toString())
                    }
                }
            }
        val file = fileFor(record.id)
        Files.newOutputStream(file).use { props.store(it, "Anonymous room — local store, do not share") }
        PrivateFileOps.setPrivateFile(file)
    }

    fun delete(id: Long) {
        Files.deleteIfExists(fileFor(id))
    }

    private fun load(path: Path): RoomRecord {
        val props =
            Properties().apply {
                Files.newInputStream(path).use { load(it) }
            }
        val id = java.lang.Long.parseUnsignedLong(props.required("id"), 16)
        val members = mutableListOf<RoomMember>()
        var index = 0
        while (props.containsKey("member.$index.pub")) {
            members +=
                RoomMember(
                    publicKey = decode(props.required("member.$index.pub")),
                    name = props.required("member.$index.name"),
                    status = MemberStatus.valueOf(props.required("member.$index.status")),
                    clientAuthPrivate = props.getProperty("member.$index.authPriv")?.let(::decode),
                    wrappedRoomKey = props.getProperty("member.$index.wrappedKey")?.let(::decode),
                    address = props.getProperty("member.$index.address"),
                    inviteExpiryEpochSeconds = props.getProperty("member.$index.inviteExpiry")?.toLong(),
                )
            index++
        }
        return RoomRecord(
            id = id,
            name = props.required("name"),
            type = RoomType.valueOf(props.required("type")),
            isFounder = props.required("isFounder").toBooleanStrict(),
            founderAddress = props.getProperty("founderAddress"),
            founderPublicKey = props.getProperty("founderPublicKey")?.let(::decode),
            serviceSeed = decode(props.required("serviceSeed")),
            serviceAddress = props.required("serviceAddress"),
            roomKey = decode(props.required("roomKey")),
            keyVersion = props.required("keyVersion").toInt(),
            entryKey = props.getProperty("entryKey"),
            myName = props.required("myName"),
            members = members,
        )
    }

    private fun fileFor(id: Long): Path = roomDir.resolve("%016x.properties".format(id))

    private fun Properties.required(key: String): String = getProperty(key) ?: error("missing property: $key")

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decode(value: String): ByteArray = Base64.getDecoder().decode(value)

    private companion object {
        const val SUFFIX = ".properties"
    }
}
