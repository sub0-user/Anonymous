package org.server.anonymous.business

import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Minimal process surface so TorNodeManager can be tested without a real process. */
interface TorProcess {
    fun start(): TorProcessManager.TorPorts

    fun cookieFile(): Path

    fun stop()
}

/**
 * Owns the bundled Tor process: extracts the binary from the jar/jlink module
 * (manifest-driven, so it works on any classloader), spawns it with a fresh data dir
 * and loopback-only control/socks ports, and stops it cleanly.
 */
class TorProcessManager(
    private val dataDir: Path,
) : TorProcess {
    data class TorPorts(
        val controlPort: Int,
        val socksPort: Int,
    )

    private var process: Process? = null
    private var torRoot: Path? = null
    private var ports: TorPorts? = null

    override fun start(): TorPorts {
        Files.createDirectories(dataDir)
        val root = dataDir.resolve("tor")
        torRoot = root
        val binary = extractAndResolveBinary(root)
        val controlPort = freePort()
        val socksPort = freePort()
        val builder =
            ProcessBuilder(
                binary.toString(),
                "--DataDirectory",
                root.resolve("data").toString(),
                "--ControlPort",
                "127.0.0.1:$controlPort",
                "--SocksPort",
                "127.0.0.1:$socksPort",
                "--ClientOnly",
                "1",
                "--Log",
                "notice file ${dataDir.resolve("tor.log")}",
            )
        builder.redirectErrorStream(true)
        builder.redirectOutput(dataDir.resolve("tor.stdout").toFile())
        val p = builder.start()
        process = p
        ports = TorPorts(controlPort, socksPort)
        return ports!!
    }

    override fun cookieFile(): Path {
        val root = torRoot ?: error("Tor not started")
        return root.resolve("data/control_auth_cookie")
    }

    fun isRunning(): Boolean = process?.isAlive == true

    override fun stop() {
        process?.let { p ->
            p.destroy()
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly()
            }
        }
        process = null
    }

    private fun extractAndResolveBinary(root: Path): Path {
        val existing = torBinaryCandidates(root).firstOrNull { Files.isExecutable(it) }
        if (existing != null) return existing
        val platform = torPlatform()
        val base = "tor/$platform"
        val manifest =
            TorProcessManager::class.java.getResourceAsStream("$base/manifest.txt")
                ?: error("Bundled Tor missing for '$platform' — run ./gradlew downloadTor")
        Files.createDirectories(root)
        manifest.bufferedReader().useLines { lines ->
            for (rel in lines) {
                if (rel.isBlank()) continue
                val out = root.resolve(rel)
                Files.createDirectories(out.parent)
                TorProcessManager::class.java
                    .getResourceAsStream("$base/$rel")
                    ?.use { src -> Files.newOutputStream(out).use { src.copyTo(it) } }
                    ?: error("Bundled Tor file missing: $rel")
            }
        }
        val binary =
            torBinaryCandidates(root).firstOrNull { Files.isExecutable(it) }
                ?: error("No executable tor found in the bundle")
        if (!System.getProperty("os.name").lowercase().contains("win")) {
            binary.toFile().setExecutable(true)
        }
        return binary
    }

    private fun torBinaryCandidates(root: Path): List<Path> =
        listOf(root.resolve("tor/bin/tor"), root.resolve("tor/tor"), root.resolve("tor/debug/tor"))

    private fun torPlatform(): String {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        val osKey =
            when {
                os.contains("win") -> "windows"
                os.contains("mac") || os.contains("darwin") -> "macos"
                else -> "linux"
            }
        val archKey = if (arch.contains("aarch64") || arch.contains("arm64")) "aarch64" else "x86_64"
        return "$osKey-$archKey"
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
}
