package org.server.anonymous.business

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

class IdentityServiceTest {
    /** Manual temp dir (JUnit's @TempDir needs JPMS opens we avoid). */
    private fun newTempDir(): Path =
        Files.createTempDirectory("anonymous-identity-test").also {
            it.toFile().deleteOnExit()
        }

    @Test
    fun `generates a 32-byte ed25519 seed`() {
        val identity = IdentityService(newTempDir()).getOrCreate()
        assertEquals(32, identity.seed.size)
    }

    @Test
    fun `identity is stable across restarts`() {
        val dir = newTempDir()
        val service = IdentityService(dir)
        val first = service.getOrCreate()
        val second = service.getOrCreate()
        assertArrayEquals(first.seed, second.seed)
    }

    @Test
    fun `two fresh identities differ`() {
        val a = IdentityService(newTempDir()).getOrCreate()
        val b = IdentityService(newTempDir()).getOrCreate()
        assertNotEquals(a.seed.toList(), b.seed.toList())
    }

    @Test
    fun `persists seed to identity properties`() {
        val dir = newTempDir()
        IdentityService(dir).getOrCreate()
        assertTrue(Files.exists(dir.resolve("identity.properties")))
    }

    @Test
    fun `seed round-trips through the properties file`() {
        val dir = newTempDir()
        val first = IdentityService(dir).getOrCreate()
        val reloaded = IdentityService(dir).getOrCreate()
        assertArrayEquals(first.seed, reloaded.seed)
    }

    @Test
    fun `restricts file permissions on posix`() {
        val os = System.getProperty("os.name").lowercase()
        assumeFalse(os.contains("win"))
        val dir = newTempDir()
        IdentityService(dir).getOrCreate()
        val perms = Files.getPosixFilePermissions(dir.resolve("identity.properties"))
        assertTrue(PosixFilePermission.OWNER_READ in perms)
        assertTrue(PosixFilePermission.OWNER_WRITE in perms)
        assertTrue(PosixFilePermission.GROUP_READ !in perms)
        assertTrue(PosixFilePermission.OTHERS_READ !in perms)
    }
}
