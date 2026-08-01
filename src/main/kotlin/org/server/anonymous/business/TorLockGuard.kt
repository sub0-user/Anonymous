package org.server.anonymous.business

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Reaps a tor left over from a hard-killed Anonymous session so a fresh one can take the data
 * dir. Tor writes its PID into data/lock; a dead owner is stale (SIGKILL leaves the file), a
 * live owner can only be a leftover tor of a previous session using this exact data dir — we
 * are the only writer of that path, so it is ours to reap. Anything else is left for tor to
 * report itself.
 */
internal class TorLockGuard(
    private val dataDir: Path,
) {
    fun clearStaleLock() {
        val lock = dataDir.resolve("tor/data/lock")
        if (!Files.exists(lock)) return
        val pid = runCatching { Files.readString(lock).trim().toIntOrNull() }.getOrNull()
        if (pid == null) return
        if (isDead(pid)) {
            runCatching { Files.deleteIfExists(lock) }
        } else if (isOurLeftoverTor(pid)) {
            killAndWait(pid)
            runCatching { Files.deleteIfExists(lock) }
        }
    }

    private fun isDead(pid: Int): Boolean = ProcessHandle.of(pid.toLong()).map { !it.isAlive }.orElse(true)

    private fun isOurLeftoverTor(pid: Int): Boolean {
        val owner = ProcessHandle.of(pid.toLong()).orElse(null) ?: return false
        val cmdline = owner.info().commandLine().orElse("")
        return cmdline.contains("tor") && cmdline.contains(dataDir.toString())
    }

    private fun killAndWait(pid: Int) {
        ProcessHandle.of(pid.toLong()).ifPresent { it.destroy() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (ProcessHandle.of(pid.toLong()).map { it.isAlive }.orElse(false) && System.nanoTime() < deadline) {
            Thread.sleep(200)
        }
    }
}
