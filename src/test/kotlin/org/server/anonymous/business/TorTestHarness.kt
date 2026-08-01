package org.server.anonymous.business

import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Shared harness for the real-Tor integration tests. Boots a node's tor, waits for
 * bootstrap, registers an onion service, and polls for reachability/conditions. Kept as
 * one object so the identity-messaging and room capstones read top-down instead of each
 * carrying its own copy of the transport plumbing.
 *
 * Tor's control replies can stall for the full socket timeout (60s) while tor is busy
 * with network-heavy phases (bootstrap, descriptor uploads). Every control command that
 * can stall is therefore retried on a FRESH connection — a stalled connection is
 * discarded, never reused, so a slow-but-alive network is exercised rather than failed.
 */
object TorTestHarness {
    /** Boots tor, waits for bootstrap and registers the identity service; returns the address. */
    fun onlineAddress(
        process: TorProcessManager,
        ports: TorProcessManager.TorPorts,
        identity: Identity,
        inbound: ServerSocket,
    ): String {
        val control = connectedControl(process, ports)
        val live = waitForBootstrap(control, process, ports)
        try {
            return addOnionWithRetry(live, process, ports, identity.seed, inbound.localPort)
        } finally {
            live.close()
        }
    }

    /** A connected, cookie-authenticated control client for a running node. */
    fun authenticatedControl(
        process: TorProcessManager,
        ports: TorProcessManager.TorPorts,
    ): ControlProtocolClient = connectedControl(process, ports)

    /** Polls until the condition holds or the timeout passes; returns the last attempt. */
    fun poll(
        timeoutMs: Long,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(500)
        }
        return condition()
    }

    /** Polls until the condition holds, then fails the test if it never did. */
    fun await(
        timeoutMs: Long = 300_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(500)
        }
        error("condition not met within ${timeoutMs}ms")
    }

    /**
     * Polls bootstrap progress. Each probe runs on a fresh connection: a probe that stalls
     * past the control socket timeout is abandoned and the next probe reconnects, so a
     * tor that is merely busy still counts as progressing instead of burning its budget on
     * one wedged socket.
     */
    private fun waitForBootstrap(
        control: ControlProtocolClient,
        process: TorProcessManager,
        ports: TorProcessManager.TorPorts,
    ): ControlProtocolClient {
        var current = control
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(480)
        while (System.nanoTime() < deadline) {
            val progress = runCatching { current.bootstrapProgress() }.getOrNull()
            if (progress != null && progress >= 100) return current
            if (progress == null) {
                runCatching { current.close() }
                current = connectedControl(process, ports)
            }
            Thread.sleep(1000)
        }
        error("tor did not bootstrap within 480s")
    }

    /**
     * Registers a service, retrying on a fresh connection when tor stalls. A stalled
     * ADD_ONION is abandoned and the retry reconnects, so a busy tor gets many chances.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // control failure means "retry fresh"
    private fun addOnionWithRetry(
        control: ControlProtocolClient,
        process: TorProcessManager,
        ports: TorProcessManager.TorPorts,
        seed: ByteArray,
        targetPort: Int,
    ): String {
        var current = control
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(240)
        while (System.nanoTime() < deadline) {
            try {
                return current.addOnionService(seed, virtualPort = 80, "127.0.0.1", targetPort)
            } catch (t: Throwable) {
                runCatching { current.close() }
                current = connectedControl(process, ports)
                Thread.sleep(2000)
            }
        }
        error("ADD_ONION never succeeded for the identity service")
    }

    private fun connectedControl(
        process: TorProcessManager,
        ports: TorProcessManager.TorPorts,
    ): ControlProtocolClient {
        val control = ControlProtocolClient()
        connectWithRetry(control, ports.controlPort)
        val cookie = waitForCookie(process.cookieFile())
        authenticateWithRetry(control, cookie)
        return control
    }

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

    private fun authenticateWithRetry(
        control: TorControl,
        cookie: ByteArray,
    ) {
        var lastError: Throwable? = null
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (System.nanoTime() < deadline) {
            try {
                control.authenticate(cookie)
                return
            } catch (t: Throwable) {
                lastError = t
                Thread.sleep(1000)
            }
        }
        error("AUTHENTICATE never succeeded: $lastError")
    }

    private fun waitForCookie(cookieFile: Path): ByteArray {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < deadline) {
            if (Files.exists(cookieFile)) return Files.readAllBytes(cookieFile)
            Thread.sleep(500)
        }
        error("tor never wrote the control auth cookie")
    }
}
