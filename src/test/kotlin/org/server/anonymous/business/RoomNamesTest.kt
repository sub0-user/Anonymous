package org.server.anonymous.business

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.server.anonymous.business.model.RoomMember
import org.server.anonymous.business.model.RoomRecord
import org.server.anonymous.business.model.RoomType

class RoomNamesTest {
    private val room =
        RoomRecord(
            id = 1,
            name = "dev den",
            type = RoomType.PRIVATE,
            isFounder = true,
            founderAddress = null,
            founderPublicKey = null,
            serviceSeed = ByteArray(32),
            serviceAddress = "a".repeat(56) + ".onion",
            roomKey = ByteArray(32),
            keyVersion = 1,
            entryKey = null,
            myName = "raven",
            members =
                listOf(
                    RoomMember(ByteArray(32) { 1 }, "alice"),
                    RoomMember(ByteArray(32) { 2 }, "bob"),
                ),
        )

    @Test
    fun `normalize trims and caps the length`() {
        assertEquals("neo", RoomNames.normalize("  neo  "))
        assertEquals("x".repeat(32), RoomNames.normalize("x".repeat(80)))
    }

    @Test
    fun `blank names are invalid`() {
        assertFalse(RoomNames.isValid(""))
        assertFalse(RoomNames.isValid("   "))
    }

    @Test
    fun `uniqueness is case insensitive`() {
        assertFalse(RoomNames.isUnique(room, "ALICE"))
        assertFalse(RoomNames.isUnique(room, "Bob"))
        assertTrue(RoomNames.isUnique(room, "carl"))
    }
}
