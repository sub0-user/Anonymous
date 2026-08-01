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
 *
 * @Suppress TooManyFunctions: one cohesive node lifecycle; splitting it would scatter the
 * startup pipeline.
 */
@Suppress("TooManyFunctions")
class TorNodeManager(
    private val identityService: IdentityService,
    private val torProcess: TorProcess,
    private val controlFactory: () -> TorControl,
    /** How often the watchdog checks tor liveness (Phase A3); short in tests, 5s in the app. */
    private val watchdogIntervalMillis: Long = 5_000,
    /** Minimum gap between recovery attempts so a dying tor never spins the CPU. */
    private val recoveryCooldownMillis: Long = 10_000,
) : NodeStatusSource {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "tor-node").apply { isDaemon = true } }
    private val watchdogExecutor =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "tor-watchdog").apply { isDaemon = true } }
    private val listeners = CopyOnWriteArrayList<(NodeStatus) -> Unit>()
    private var currentStatus: NodeStatus = NodeStatus.Offline("not started")
    private var control: TorControl? = null
    private var inbound: ServerSocket? = null
    private var started = false
    private var nodeWasUp = false
    private var lastRecoveryAttemptAt = 0L

    override fun addStatusListener(listener: (NodeStatus) -> Unit) {
        listeners += listener
    }

    override fun status(): NodeStatus = currentStatus

    // Defensive boundary: any failure must surface as Offline, never die silently.
    fun start() {
        started = true
        executor.execute {
            val lastError = startWithRetry(attempts = 3)
            if (lastError != null) {
                System.err.println("[tor-node] start failed: ${lastError.message}")
                setStatus(NodeStatus.Offline(lastError.message ?: lastError::class.simpleName ?: "error"))
                runCatching { cleanup() } // keep the error reason, just tear down the process
            }
        }
        watchdogExecutor.scheduleWithFixedDelay(
            { runCatching { watchdogTick() } },
            watchdogIntervalMillis,
            watchdogIntervalMillis,
            TimeUnit.MILLISECONDS,
        )
    }

    /**
     * Phase A3 watchdog: tor can die mid-session (OOM, crash, kill). When the node was up and
     * the process is gone, tear down, re-spawn with the same identity (same onion address) and
     * re-register the services. A failed recovery retries on later ticks, gated by the cooldown.
     */
    private fun watchdogTick() {
        if (!started) return
        if (nodeWasUp && !torProcess.isRunning() && currentStatus !is NodeStatus.Bootstrapping) {
            setStatus(NodeStatus.Offline("tor died — restarting"))
            scheduleRecovery()
        }
    }

    private fun scheduleRecovery() {
        val now = System.currentTimeMillis()
        if (now - lastRecoveryAttemptAt < recoveryCooldownMillis) return
        lastRecoveryAttemptAt = now
        executor.execute {
            runCatching { cleanup() }
            val lastError = startWithRetry(attempts = 3)
            if (lastError != null) {
                System.err.println("[tor-node] recovery failed: ${lastError.message}")
                setStatus(NodeStatus.Offline(lastError.message ?: lastError::class.simpleName ?: "tor died"))
            }
        }
    }

    // A leaked tor from a hard-killed session holds the data-dir lock and makes the first
    // spawn exit immediately; TorProcessManager reaps it, so retry the whole pipeline.
    @Suppress("TooGenericExceptionCaught") // any node failure retries before surfacing as Offline
    private fun startWithRetry(attempts: Int): Throwable? {
        var lastError: Throwable? = null
        for (attempt in 1..attempts) {
            try {
                startOnce()
                return null
            } catch (t: Throwable) {
                lastError = t
                runCatching { cleanup() } // free ports + kill the dead process before retrying
                if (attempt < attempts) Thread.sleep(1000)
            }
        }
        return lastError
    }

    private fun startOnce() {
        val identity = identityService.getOrCreate()
        val ports = torProcess.start()
        setStatus(NodeStatus.Bootstrapping(0))
        val c = controlFactory()
        control = c
        connectWithRetry(c, ports.controlPort)
        val cookie = Files.readAllBytes(torProcess.cookieFile())
        c.authenticate(cookie)
        waitForBootstrap(c)
        val inboundSocket = ServerSocket(0) // Phase 3: the P2P messaging listener target
        inbound = inboundSocket
        val address = c.addOnionService(identity.seed, virtualPort = 80, "127.0.0.1", inboundSocket.localPort)
        nodeWasUp = true
        setStatus(NodeStatus.Online(address, ports.socksPort))
    }

    /** The socket Tor routes inbound onion connections to; null while offline. */
    val inboundSocket: ServerSocket?
        get() = inbound

    /** The connected+authenticated control client (used for room services); null while offline. */
    val controlClient: TorControl?
        get() = control

    /** Tor's ClientOnionAuthDir — joined room services' `.auth_private` files live here. */
    fun clientAuthDir(): java.nio.file.Path = torProcess.clientAuthDir()

    fun stop() {
        started = false
        watchdogExecutor.shutdownNow()
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

    /** Tor's control listener opens shortly after spawn — retry refused/reset connects. */
    @Suppress("TooGenericExceptionCaught") // transient connect failures are retried
    private fun connectWithRetry(
        c: TorControl,
        controlPort: Int,
    ) {
        var lastError: Throwable? = null
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (System.nanoTime() < deadline) {
            if (!torProcess.isRunning()) error("tor process exited during startup (stale data-dir lock?)")
            try {
                c.connect("127.0.0.1", controlPort)
                return
            } catch (t: Throwable) {
                lastError = t
                Thread.sleep(1000)
            }
        }
        error("could not connect to tor control port: $lastError")
    }

    private fun waitForBootstrap(c: TorControl) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
        while (System.nanoTime() < deadline) {
            if (!torProcess.isRunning()) error("tor process exited during bootstrap")
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
