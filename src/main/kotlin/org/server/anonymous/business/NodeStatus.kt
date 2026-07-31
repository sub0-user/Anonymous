package org.server.anonymous.business

/** Current state of the user's Tor node, as surfaced to the UI (spec §3). */
sealed interface NodeStatus {
    data class Offline(
        val reason: String,
    ) : NodeStatus

    data class Bootstrapping(
        val progress: Int,
    ) : NodeStatus

    data class Online(
        val address: String,
    ) : NodeStatus
}

/** Observable status source — ViewModels consume this, never TorNodeManager directly. */
interface NodeStatusSource {
    fun addStatusListener(listener: (NodeStatus) -> Unit)

    fun status(): NodeStatus
}
