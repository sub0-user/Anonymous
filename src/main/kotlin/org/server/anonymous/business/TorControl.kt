package org.server.anonymous.business

/** Thin abstraction over the Tor control protocol (PATTERNS.md §5: interface + fake). */
interface TorControl {
    fun connect(
        host: String,
        port: Int,
    )

    fun authenticate(cookie: ByteArray)

    /** Returns bootstrap progress 0-100, or null when the status query yields nothing. */
    fun bootstrapProgress(): Int?

    /** Creates a v3 onion service from the Ed25519 seed; returns the .onion address. */
    fun addOnionService(
        seed: ByteArray,
        virtualPort: Int,
        targetHost: String,
        targetPort: Int,
    ): String

    fun deleteOnionService(address: String)

    /** Reloads Tor's configuration (e.g. after writing a client-auth file). */
    fun signalHup()

    fun close()
}
