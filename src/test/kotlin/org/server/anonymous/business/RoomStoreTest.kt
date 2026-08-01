package org.server.anonymous.business

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.server.anonymous.business.model.MemberStatus
import org.server.anonymous.business.model.RoomMember
import org.server.anonymous.business.model.RoomRecord
import org.server.anonymous.business.model.RoomType
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

class RoomStoreTest {
    private fun newDir(): Path = Files.createTempDirectory("anonymous-room-store").also { it.toFile().deleteOnExit() }

    private fun seed(offset: Int): ByteArray = ByteArray(32) { (it + offset).toByte() }

    private val memberKeys =
        listOf(
            RoomMember(seed(11), "raven"),
            RoomMember(
                publicKey = seed(12),
                name = "neo",
                status = MemberStatus.INVITED,
                clientAuthPrivate = seed(21),
                wrappedRoomKey = ByteArray(28) { 22 },
                address = "b".repeat(56) + ".onion",
                inviteExpiryEpochSeconds = 1_752_000_000L,
            ),
        )

    private fun sampleRecord(
        id: Long = 1,
        type: RoomType = RoomType.PRIVATE,
    ): RoomRecord =
        RoomRecord(
            id = id,
            name = "dev den",
            type = type,
            isFounder = true,
            founderAddress = null,
            founderPublicKey = null,
            serviceSeed = seed(1),
            serviceAddress = "a".repeat(56) + ".onion",
            roomKey = seed(5),
            keyVersion = 3,
            entryKey = if (type == RoomType.PUBLIC) EntryKey.generate() else null,
            myName = "raven",
            members = memberKeys,
        )

    @Test
    fun `private room roundtrips through save and load`() {
        val store = RoomStore(newDir())
        val record = sampleRecord()
        store.save(record)
        val loaded = store.loadAll().single()
        assertRoundTrip(record, loaded)
    }

    @Test
    fun `public room roundtrips with entry key and member flags`() {
        val store = RoomStore(newDir())
        val record = sampleRecord(type = RoomType.PUBLIC)
        store.save(record)
        val loaded = store.loadAll().single()
        assertRoundTrip(record, loaded)
        assertEquals(RoomType.PUBLIC, loaded.type)
        assertEquals(record.entryKey, loaded.entryKey)
    }

    @Test
    fun `member room with founder address roundtrips`() {
        val store = RoomStore(newDir())
        val record =
            sampleRecord().copy(
                isFounder = false,
                founderAddress = "b".repeat(56) + ".onion",
            )
        store.save(record)
        val loaded = store.loadAll().single()
        assertEquals(false, loaded.isFounder)
        assertEquals(record.founderAddress, loaded.founderAddress)
    }

    @Test
    fun `multiple rooms load sorted by id`() {
        val store = RoomStore(newDir())
        store.save(sampleRecord(id = 3))
        store.save(sampleRecord(id = 1))
        store.save(sampleRecord(id = 2))
        assertEquals(listOf(1L, 2L, 3L), store.loadAll().map { it.id })
    }

    @Test
    fun `delete removes the room`() {
        val store = RoomStore(newDir())
        store.save(sampleRecord())
        store.delete(1)
        assertTrue(store.loadAll().isEmpty())
    }

    @Test
    fun `empty store loads empty`() {
        assertEquals(emptyList<RoomRecord>(), RoomStore(newDir()).loadAll())
    }

    @Test
    fun `corrupt room file is skipped`() {
        val dir = newDir()
        val store = RoomStore(dir)
        store.save(sampleRecord(id = 1))
        val corrupt = dir.resolve("%016x.properties".format(2))
        Properties().apply { setProperty("name", "no id here") }.let { props ->
            Files.newOutputStream(corrupt).use { props.store(it, null) }
        }
        assertEquals(listOf(1L), store.loadAll().map { it.id })
    }

    private fun assertRoundTrip(
        expected: RoomRecord,
        actual: RoomRecord,
    ) {
        assertEquals(expected.id, actual.id)
        assertEquals(expected.name, actual.name)
        assertEquals(expected.type, actual.type)
        assertEquals(expected.isFounder, actual.isFounder)
        assertEquals(expected.founderAddress, actual.founderAddress)
        if (expected.founderPublicKey == null) {
            assertEquals(null, actual.founderPublicKey)
        } else {
            assertArrayEquals(expected.founderPublicKey, actual.founderPublicKey)
        }
        assertArrayEquals(expected.serviceSeed, actual.serviceSeed)
        assertEquals(expected.serviceAddress, actual.serviceAddress)
        assertArrayEquals(expected.roomKey, actual.roomKey)
        assertEquals(expected.keyVersion, actual.keyVersion)
        assertEquals(expected.entryKey, actual.entryKey)
        assertEquals(expected.myName, actual.myName)
        assertEquals(expected.members.size, actual.members.size)
        expected.members.zip(actual.members).forEach { (a, b) ->
            assertArrayEquals(a.publicKey, b.publicKey)
            assertEquals(a.name, b.name)
            assertEquals(a.status, b.status)
            if (a.clientAuthPrivate == null) {
                assertEquals(null, b.clientAuthPrivate)
            } else {
                assertArrayEquals(a.clientAuthPrivate, b.clientAuthPrivate)
            }
            if (a.wrappedRoomKey == null) {
                assertEquals(null, b.wrappedRoomKey)
            } else {
                assertArrayEquals(a.wrappedRoomKey, b.wrappedRoomKey)
            }
            assertEquals(a.address, b.address)
            assertEquals(a.inviteExpiryEpochSeconds, b.inviteExpiryEpochSeconds)
        }
    }
}
