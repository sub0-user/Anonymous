package org.server.anonymous.business

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TorNodeManagerTest {
    private class FakeTorProcess(
        private val cookie: Path,
    ) : TorProcess {
        override fun start(): TorProcessManager.TorPorts = TorProcessManager.TorPorts(9051, 9052)

        override fun cookieFile(): Path = cookie

        override fun isRunning(): Boolean = true

        override fun stop() = Unit
    }

    private class FakeTorControl : TorControl {
        val addedSeeds = mutableListOf<ByteArray>()
        var deleted = false
        var progressSequence = listOf(10, 60, 100)
        private var index = 0

        override fun connect(
            host: String,
            port: Int,
        ) = Unit

        override fun authenticate(cookie: ByteArray) = Unit

        override fun bootstrapProgress(): Int? = progressSequence.getOrNull(index++)

        override fun addOnionService(
            seed: ByteArray,
            virtualPort: Int,
            targetHost: String,
            targetPort: Int,
        ): String {
            addedSeeds += seed
            return "a".repeat(56) + ".onion"
        }

        override fun deleteOnionService(address: String) {
            deleted = true
        }

        override fun close() = Unit
    }

    private fun newTempDir(): Path =
        Files.createTempDirectory("anonymous-node-test").also {
            it.toFile().deleteOnExit()
        }

    @Test
    fun `reaches online and notifies listeners`() {
        val latch = CountDownLatch(1)
        val statuses = mutableListOf<NodeStatus>()
        val dir = newTempDir()
        val cookie = dir.resolve("cookie")
        Files.write(cookie, byteArrayOf(1, 2, 3))
        val manager =
            TorNodeManager(
                IdentityService(dir.resolve("id")),
                FakeTorProcess(cookie),
                { FakeTorControl() },
            )
        manager.addStatusListener {
            statuses += it
            if (it is NodeStatus.Online) latch.countDown()
        }
        manager.start()
        assertTrue(latch.await(15, TimeUnit.SECONDS), "node did not come online")
        val final = manager.status()
        assertTrue(final is NodeStatus.Online)
        assertEquals("a".repeat(56) + ".onion", (final as NodeStatus.Online).address)
        assertTrue(statuses.any { it is NodeStatus.Bootstrapping && it.progress == 60 })
        manager.stop()
    }

    @Test
    fun `reports offline with reason on failure`() {
        val dir = newTempDir()
        val manager =
            TorNodeManager(
                IdentityService(dir.resolve("id")),
                object : TorProcess {
                    override fun start(): TorProcessManager.TorPorts = error("boom")

                    override fun cookieFile(): Path = dir.resolve("cookie")

                    override fun isRunning(): Boolean = true

                    override fun stop() = Unit
                },
                { FakeTorControl() },
            )
        manager.start()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline && (manager.status() as? NodeStatus.Offline)?.reason != "boom") {
            Thread.sleep(50)
        }
        assertTrue(manager.status() is NodeStatus.Offline)
        assertEquals("boom", (manager.status() as NodeStatus.Offline).reason)
        manager.stop()
    }

    @Test
    fun `recovers when the spawned tor dies from a stale data-dir lock`() {
        val dir = newTempDir()
        val cookie = dir.resolve("cookie")
        Files.write(cookie, byteArrayOf(1, 2, 3))
        val spawnCount = AtomicInteger(0)
        val process =
            object : TorProcess {
                // First spawn mimics a leaked tor still holding the lock: the new tor exits,
                // so isRunning() reports false and the connect attempt must abort fast.
                override fun start(): TorProcessManager.TorPorts {
                    val n = spawnCount.incrementAndGet()
                    val port = if (n == 1) 9051 else 9053
                    return TorProcessManager.TorPorts(port, port + 1)
                }

                override fun cookieFile(): Path = cookie

                override fun isRunning(): Boolean = spawnCount.get() >= 2

                override fun stop() = Unit
            }
        val manager =
            TorNodeManager(
                IdentityService(dir.resolve("id")),
                process,
                { FakeTorControl() },
            )
        val latch = CountDownLatch(1)
        manager.addStatusListener { if (it is NodeStatus.Online) latch.countDown() }
        manager.start()
        assertTrue(latch.await(15, TimeUnit.SECONDS), "node did not recover from stale lock")
        assertTrue(manager.status() is NodeStatus.Online)
        manager.stop()
    }
}
