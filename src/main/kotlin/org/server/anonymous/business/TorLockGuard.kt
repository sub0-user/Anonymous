package org.server.anonymous.business

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Reaps a tor left over from a hard-killed Anonymous session so a fresh one can take the data
 * dir. Tor's data/lock is only an flock anchor — it carries no PID — so identify a leftover by
 * scanning live processes for one whose command line references our data dir; only our own tor
 * ever does. After the holder is gone (or never existed), the stale lock file is removed.
 */
internal class TorLockGuard(
    private val dataDir: Path,
) {
    fun clearStaleLock() {
        val lock = dataDir.resolve("tor/data/lock")
        if (!Files.exists(lock)) return
        killLeftovers()
        // The holder is gone (or there never was one): the lock anchor is stale. Deleting it is
        // safe even for an unidentified holder, which keeps its (unlinked) inode lock harmlessly.
        runCatching { Files.deleteIfExists(lock) }
    }

    private fun killLeftovers() {
        val leftovers = mutableListOf<ProcessHandle>()
        for (p in ProcessHandle.allProcesses()) {
            val cmdline = p.info().commandLine().orElse("")
            if (cmdline.contains(dataDir.toString())) leftovers += p
        }
        for (leftover in leftovers) leftover.destroy()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (leftovers.any { it.isAlive } && System.nanoTime() < deadline) {
            Thread.sleep(200)
        }
    }
}
