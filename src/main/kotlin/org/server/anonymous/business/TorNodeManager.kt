package org.server.anonymous.business

import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Facade: identity → process → cookie auth → bootstrap → ADD_ONION → status source.
 * All node work happens off the caller thread; status listeners are notified from
 * this manager's single executor (ViewModels hop to the FX thread themselves).
 */
class TorNodeManager(
    private val identityService: IdentityService,
    private val torProcess: TorProcess,
    private val controlFactory: () -> TorControl,
) : NodeStatusSource {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "tor-node").apply { isDaemon = true } }
    private val listeners = CopyOnWriteArrayList<(NodeStatus) -> Unit>()
    private var currentStatus: NodeStatus = NodeStatus.Offline("not started")
    private var control: TorControl? = null
    private var inbound: ServerSocket? = null

    override fun addStatusListener(listener: (NodeStatus) -> Unit) {
        listeners += listener
    }

    override fun status(): NodeStatus = currentStatus

    // Defensive boundary: any failure must surface as Offline, never die silently.
    @Suppress("TooGenericExceptionCaught")
    fun start() {
        executor.execute {
            try {
                val identity = identityService.getOrCreate()
                val ports = torProcess.start()
                setStatus(NodeStatus.Bootstrapping(0))
                val c = controlFactory()
                control = c
                c.connect("127.0.0.1", ports.controlPort)
                val cookie = Files.readAllBytes(torProcess.cookieFile())
                c.authenticate(cookie)
                waitForBootstrap(c)
                val inboundSocket = ServerSocket(0) // reserved target port (Phase 3 listener)
                inbound = inboundSocket
                val address = c.addOnionService(identity.seed, virtualPort = 80, "127.0.0.1", inboundSocket.localPort)
                setStatus(NodeStatus.Online(address))
            } catch (t: Throwable) {
                setStatus(NodeStatus.Offline(t.message ?: t::class.simpleName ?: "error"))
                runCatching { cleanup() } // keep the error reason, just tear down the process
            }
        }
    }

    fun stop() {
        executor.execute {
            cleanup()
            setStatus(NodeStatus.Offline("stopped"))
        }
    }

    private fun cleanup() {
        control?.let { c -> runCatching { c.close() } }
        inbound?.let { runCatching { it.close() } }
        torProcess.stop()
        control = null
        inbound = null
    }

    private fun waitForBootstrap(c: TorControl) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
        while (System.nanoTime() < deadline) {
            val progress = c.bootstrapProgress()
            if (progress != null) {
                if (progress >= 100) return
                setStatus(NodeStatus.Bootstrapping(progress))
            }
            Thread.sleep(1000)
        }
        error("Tor did not bootstrap within 120s")
    }

    private fun setStatus(status: NodeStatus) {
        currentStatus = status
        listeners.forEach { it(status) }
    }
}
