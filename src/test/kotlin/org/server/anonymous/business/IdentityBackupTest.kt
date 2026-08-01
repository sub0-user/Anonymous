package org.server.anonymous.business

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import javax.crypto.AEADBadTagException

class IdentityBackupTest {
    private fun newTempDir(): Path =
        Files.createTempDirectory("anonymous-backup-test").also {
            it.toFile().deleteOnExit()
        }

    @Test
    fun `export and import roundtrip with the correct passphrase`() {
        val seed = ByteArray(32) { it.toByte() }
        val backup = IdentityBackup.export(seed, "hunter2".toCharArray())
        val restored = IdentityBackup.import(backup, "hunter2".toCharArray())
        assertArrayEquals(seed, restored)
    }

    @Test
    fun `wrong passphrase is rejected`() {
        val backup = IdentityBackup.export(ByteArray(32) { 1 }, "correct horse".toCharArray())
        assertThrows(AEADBadTagException::class.java) {
            IdentityBackup.import(backup, "battery staple".toCharArray())
        }
    }

    @Test
    fun `tampered backup is rejected`() {
        val backup = IdentityBackup.export(ByteArray(32) { 1 }, "hunter2".toCharArray())
        val tampered = backup.copyOf().also { it[it.size - 5] = (it[it.size - 5].toInt() xor 1).toByte() }
        assertThrows(AEADBadTagException::class.java) {
            IdentityBackup.import(tampered, "hunter2".toCharArray())
        }
    }

    @Test
    fun `garbage data is rejected`() {
        assertThrows(IllegalStateException::class.java) {
            IdentityBackup.import("not a backup".toByteArray(), "hunter2".toCharArray())
        }
    }

    @Test
    fun `exports are randomized per call`() {
        val seed = ByteArray(32) { 5 }
        val first = IdentityBackup.export(seed, "hunter2".toCharArray())
        val second = IdentityBackup.export(seed, "hunter2".toCharArray())
        assertFalse(first.contentEquals(second))
        assertArrayEquals(seed, IdentityBackup.import(second, "hunter2".toCharArray()))
    }

    @Test
    fun `imported seed restores the same identity in the service`() {
        val dir = newTempDir()
        val original = IdentityService(dir).getOrCreate()
        val backup = IdentityBackup.export(original.seed, "hunter2".toCharArray())
        val restoredSeed = IdentityBackup.import(backup, "hunter2".toCharArray())

        val restored = IdentityService(dir).replace(restoredSeed)
        assertArrayEquals(original.seed, restored.seed)
        // A fresh service instance reads the replaced identity back from disk.
        assertArrayEquals(original.seed, IdentityService(dir).getOrCreate().seed)
    }
}
