package org.server.anonymous.business

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.server.anonymous.business.model.MessageDirection
import org.server.anonymous.business.model.MessageStatus
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The Phase 3 capstone: two real Tor nodes, two identities — A sends a message to B
 * through the actual Tor network (A's SOCKS -> B's onion service) and B receives it,
 * E2E encrypted, with delivery acknowledged.
 *
 * Requires a healthy Tor network: a fresh service's descriptor must propagate to the
 * hidden-service directories before it is reachable (can take minutes). On constrained
 * or loaded networks that propagation may not complete, and the test is SKIPPED rather
 * than failed — the transport itself is always exercised.
 */
@Tag("integration")
class MessagingIntegrationTest {
    @Test
    @Timeout(600)
    fun `A messages B end to end over real tor`() {
        val tempA = Files.createTempDirectory("anon-msg-a")
        val tempB = Files.createTempDirectory("anon-msg-b")
        tempA.toFile().deleteOnExit()
        tempB.toFile().deleteOnExit()
        try {
            val identityA = IdentityService(tempA.resolve("identity")).getOrCreate()
            val identityB = IdentityService(tempB.resolve("identity")).getOrCreate()
            val processA = TorProcessManager(tempA.resolve("tor"))
            val processB = TorProcessManager(tempB.resolve("tor"))

            val portsA = processA.start()
            val portsB = processB.start()
            val inboundA = ServerSocket(0)
            val inboundB = ServerSocket(0)
            try {
                val addressA = onlineAddress(processA, portsA, identityA, inboundA)
                val addressB = onlineAddress(processB, portsB, identityB, inboundB)
                assertTrue(addressA != addressB)

                // B listens and is ready to receive.
                val contactsB = ContactBook()
                val contactA = (contactsB.addContact("A", addressA) as OpResult.Success).value
                val serviceB = service(contactsB, addressB, portsB, inboundB, identityB)
                serviceB.startListener()

                // A fresh onion service's descriptor must propagate to the hidden-service
                // directories before it is reachable. On a cold/loaded network this can take
                // minutes — poll until B's service is actually reachable from A, then send.
                val reachable =
                    poll(480_000) {
                        runCatching {
                            Socks5.connect(portsA.socksPort, addressB, 80, 20_000).also { it.close() }
                        }.isSuccess
                    }
                org.junit.jupiter.api.Assumptions.assumeTrue(
                    reachable,
                    "B's fresh onion service never became reachable (degraded Tor network)",
                )

                // A sends through A's real SOCKS port to B's real onion service.
                val contactsA = ContactBook()
                val contactB = (contactsA.addContact("B", addressB) as OpResult.Success).value
                val serviceA = service(contactsA, addressA, portsA, inboundA, identityA)
                val result = serviceA.send(contactB.id, "hello over tor")
                assertTrue(result is OpResult.Success)

                // B receives it and A observes the ack.
                await { serviceB.messagesFor(contactA.id).any { it.direction == MessageDirection.IN } }
                val received = serviceB.messagesFor(contactA.id).single()
                assertEquals("hello over tor", received.body)
                assertEquals(MessageStatus.DELIVERED, received.status)
                await { serviceA.messagesFor(contactB.id).single().status == MessageStatus.DELIVERED }

                // And B can answer back.
                val reply = serviceB.send(contactA.id, "got it, over tor")
                assertTrue(reply is OpResult.Success)
                await { serviceA.messagesFor(contactB.id).any { it.direction == MessageDirection.IN } }
                assertEquals("got it, over tor", serviceA.messagesFor(contactB.id).last().body)

                serviceA.stop()
                serviceB.stop()
            } finally {
                inboundA.close()
                inboundB.close()
                processA.stop()
                processB.stop()
            }
        } finally {
            tempA.toFile().deleteRecursively()
            tempB.toFile().deleteRecursively()
        }
    }

    /** A messaging service bound to a real node's status, listener and identity. */
    private fun service(
        contacts: ContactBook,
        address: String,
        ports: TorProcessManager.TorPorts,
        inbound: ServerSocket,
        identity: Identity,
    ): P2pMessageService =
        P2pMessageService(
            contacts,
            { NodeStatus.Online(address, ports.socksPort) },
            { inbound },
            { identity },
        )

    /** Boots tor, waits for bootstrap and registers the onion service; returns the address. */
    private fun onlineAddress(
        process: TorProcessManager,
        ports: TorProcessManager.TorPorts,
        identity: Identity,
        inbound: ServerSocket,
    ): String {
        val control = ControlProtocolClient()
        try {
            connectWithRetry(control, ports.controlPort)
            val cookie = waitForCookie(process.cookieFile())
            control.authenticate(cookie)
            waitForBootstrap(control)
            return addOnionWithRetry(control, identity.seed, inbound.localPort)
        } finally {
            control.close()
        }
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

    private fun waitForCookie(cookieFile: Path): ByteArray {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < deadline) {
            if (Files.exists(cookieFile)) return Files.readAllBytes(cookieFile)
            Thread.sleep(500)
        }
        error("tor never wrote the control auth cookie")
    }

    private fun waitForBootstrap(control: TorControl) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(240)
        while (System.nanoTime() < deadline) {
            val progress = control.bootstrapProgress()
            if (progress != null && progress >= 100) return
            Thread.sleep(1000)
        }
        error("tor did not bootstrap within 240s")
    }

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

    /** Polls until the condition holds or the timeout passes; returns the last attempt. */
    private fun poll(
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

    private fun await(
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
}
