package org.server.anonymous.business

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.EdECPrivateKey
import java.time.Instant
import java.util.Base64
import java.util.Properties

data class Identity(
    val seed: ByteArray,
    val createdAt: Instant,
)

/**
 * The user's Ed25519 identity — the root of the v3 onion address.
 * The seed is the only thing Tor needs (ADD_ONION ED25519-V3:<base64 seed>);
 * Tor derives the address itself, so we never reimplement derivation.
 */
class IdentityService(
    private val identityDir: Path,
) {
    private val propertiesFile: Path
        get() = identityDir.resolve("identity.properties")

    fun getOrCreate(): Identity = if (Files.exists(propertiesFile)) load() else create()

    /** Restores an identity from a backup (used by identity import). Overwrites the current seed. */
    fun replace(seed: ByteArray): Identity {
        val identity = Identity(seed, Instant.now())
        persist(identity)
        return identity
    }

    private fun create(): Identity {
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val identity = Identity(extractSeed(keyPair), Instant.now())
        persist(identity)
        return identity
    }

    private fun extractSeed(keyPair: KeyPair): ByteArray {
        val privateKey = keyPair.private as EdECPrivateKey
        return privateKey.bytes.orElseThrow { IllegalStateException("Ed25519 private key has no bytes") }
    }

    private fun load(): Identity {
        val props =
            Properties().apply {
                Files.newInputStream(propertiesFile).use { load(it) }
            }
        val seed = Base64.getDecoder().decode(props.getProperty("ed25519_seed"))
        return Identity(seed, Instant.parse(props.getProperty("created_at")))
    }

    private fun persist(identity: Identity) {
        Files.createDirectories(identityDir)
        setPrivatePermissions(identityDir, directory = true)
        val props =
            Properties().apply {
                setProperty("ed25519_seed", Base64.getEncoder().encodeToString(identity.seed))
                setProperty("created_at", identity.createdAt.toString())
            }
        Files.newOutputStream(propertiesFile).use { props.store(it, "Anonymous identity — do not share") }
        setPrivatePermissions(propertiesFile, directory = false)
    }

    @Suppress("SwallowedException") // best-effort: non-POSIX filesystems simply skip chmod
    private fun setPrivatePermissions(
        path: Path,
        directory: Boolean,
    ) {
        if (System.getProperty("os.name").lowercase().contains("win")) return
        try {
            Files.setPosixFilePermissions(
                path,
                if (directory) {
                    setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                    )
                } else {
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
                },
            )
        } catch (e: UnsupportedOperationException) {
            // Non-POSIX filesystem — best effort.
        }
    }
}
