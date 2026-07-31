package org.server.anonymous.business

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class TorProcessManagerTest {
    /** Manual temp dir (JUnit's @TempDir needs JPMS opens we avoid). */
    private fun newTempDir(): Path =
        Files.createTempDirectory("anonymous-tor-test").also {
            it.toFile().deleteOnExit()
        }

    @Test
    fun `not running before start`() {
        assertFalse(TorProcessManager(newTempDir()).isRunning())
    }

    @Test
    fun `cookie file requires a started process`() {
        val manager = TorProcessManager(newTempDir())
        assertThrows(IllegalStateException::class.java) {
            manager.cookieFile()
        }
    }
}
