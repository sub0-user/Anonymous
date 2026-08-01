package org.server.anonymous.business

import java.nio.file.Files
import java.nio.file.Path

/**
 * Installs a joined room service's client-auth private key into Tor's ClientOnionAuthDir and
 * reloads Tor (SIGNAL HUP) so this node's Tor can reach that room service. The file name is
 * the service id without ".onion" (tor's `<fingerprint>.auth_private` convention).
 */
class OnionClientAuth(
    private val authDir: () -> Path,
    private val torControl: () -> TorControl,
) {
    fun install(
        serviceAddress: String,
        privateKey: ByteArray,
    ) {
        val dir = authDir()
        Files.createDirectories(dir)
        PrivateFileOps.setPrivateDir(dir)
        val file = dir.resolve(authFileName(serviceAddress))
        Files.write(file, ClientAuthBlob.authPrivateFileContent(privateKey).toByteArray(Charsets.UTF_8))
        PrivateFileOps.setPrivateFile(file)
        torControl().signalHup()
    }

    fun remove(serviceAddress: String) {
        Files.deleteIfExists(authDir().resolve(authFileName(serviceAddress)))
        torControl().signalHup()
    }

    private fun authFileName(serviceAddress: String): String = serviceAddress.removeSuffix(".onion") + ".auth_private"
}
