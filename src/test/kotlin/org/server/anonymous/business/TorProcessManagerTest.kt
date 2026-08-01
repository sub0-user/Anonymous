package org.server.anonymous.business

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
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

    @Test
    fun `removes a stale lock left by a hard-killed tor`() {
        val dir = newTempDir()
        val lock = dir.resolve("tor/data/lock")
        Files.createDirectories(lock.parent)
        Files.writeString(lock, "999999999\n") // PID of a process that cannot exist
        TorLockGuard(dir).clearStaleLock()
        assertFalse(Files.exists(lock))
    }

    @Test
    fun `does not kill a live process that is not our tor`() {
        val dir = newTempDir()
        val lock = dir.resolve("tor/data/lock")
        Files.createDirectories(lock.parent)
        val sleeper = ProcessBuilder("/bin/sleep", "30").start()
        try {
            Files.writeString(lock, sleeper.pid().toString() + "\n")
            TorLockGuard(dir).clearStaleLock()
            assertTrue(sleeper.isAlive)
            assertTrue(Files.exists(lock))
        } finally {
            sleeper.destroyForcibly()
        }
    }

    @Test
    fun `kills a live leftover tor holding our data dir`() {
        val dir = newTempDir()
        val lock = dir.resolve("tor/data/lock")
        Files.createDirectories(lock.parent)
        // Fake a leftover tor: a live process whose path and args live inside our data dir
        // (matches how the real tor shows up in ProcessHandle.info().commandLine()). It must
        // be reaped and the stale lock removed.
        val fakeTor = dir.resolve("tor/tor")
        Files.writeString(fakeTor, "#!/bin/sh\n/bin/sleep 30\n")
        fakeTor.toFile().setExecutable(true)
        val leftover = ProcessBuilder(fakeTor.toString()).start()
        try {
            Files.writeString(lock, leftover.pid().toString() + "\n")
            TorLockGuard(dir).clearStaleLock()
            assertFalse(leftover.isAlive)
            assertFalse(Files.exists(lock))
        } finally {
            leftover.destroyForcibly()
        }
    }
}
