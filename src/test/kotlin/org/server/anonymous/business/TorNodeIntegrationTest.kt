package org.server.anonymous.business

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

@Tag("integration")
class TorNodeIntegrationTest {
    @Test
    @Timeout(420)
    fun `boots real tor and creates a stable real onion service`() {
        val temp = Files.createTempDirectory("anonymous-tor-it")
        temp.toFile().deleteOnExit()
        try {
            val identity = IdentityService(temp.resolve("identity")).getOrCreate()
            val manager = TorProcessManager(temp.resolve("tor"))
            val ports = manager.start()
            try {
                val control = ControlProtocolClient()
                connectWithRetry(control, ports.controlPort)
                val cookie = waitForCookie(manager.cookieFile())
                control.authenticate(cookie)
                waitForBootstrap(control)

                val inbound = ServerSocket(0)
                try {
                    val address = addOnionWithRetry(control, identity.seed, inbound.localPort)
                    assertTrue(Regex("^[a-z2-7]{56}\\.onion$").matches(address), "unexpected address: $address")

                    // Identity stability: same seed re-added after deletion yields the SAME address.
                    control.deleteOnionService(address)
                    val again = addOnionWithRetry(control, identity.seed, inbound.localPort)
                    assertEquals(address, again)

                    control.deleteOnionService(address)
                } finally {
                    inbound.close()
                    control.close()
                }
            } finally {
                manager.stop()
            }
        } finally {
            temp.toFile().deleteRecursively()
        }
    }

    /** Tor's control listener opens shortly after spawn — retry refused connects. */
    private fun connectWithRetry(
        control: TorControl,
        controlPort: Int,
    ) {
        var lastError: Throwable? = null
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (System.nanoTime() < deadline) {
            try {
                control.connect("127.0.0.1", controlPort)
                return
            } catch (t: Throwable) {
                lastError = t
                Thread.sleep(1000)
            }
        }
        error("could not connect to tor control port: $lastError")
    }

    private fun waitForCookie(cookieFile: Path): ByteArray {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < deadline) {
            if (Files.exists(cookieFile)) {
                return Files.readAllBytes(cookieFile)
            }
            Thread.sleep(500)
        }
        error("tor never wrote the control auth cookie")
    }

    private fun waitForBootstrap(control: TorControl) {
        // Generous: this sandbox's Tor network is variable (observed 40s to >2min).
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(240)
        while (System.nanoTime() < deadline) {
            val progress = control.bootstrapProgress()
            if (progress != null && progress >= 100) return
            Thread.sleep(1000)
        }
        error("tor did not bootstrap within 240s")
    }

    /**
     * ADD_ONION can stall while tor's network settles (the control client's 30 s socket
     * timeout bounds each attempt). Retry with backoff so a busy moment doesn't fail the test.
     */
    private fun addOnionWithRetry(
        control: TorControl,
        seed: ByteArray,
        targetPort: Int,
    ): String {
        var lastError: Throwable? = null
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(180)
        while (System.nanoTime() < deadline) {
            try {
                return control.addOnionService(seed, virtualPort = 80, "127.0.0.1", targetPort)
            } catch (t: Throwable) {
                lastError = t
                Thread.sleep(5000)
            }
        }
        error("ADD_ONION never succeeded: $lastError")
    }
}
