package org.server.anonymous.controller

import org.server.anonymous.business.NodeStatus
import org.server.anonymous.business.NodeStatusSource

class FakeNodeStatusSource : NodeStatusSource {
    private val listeners = mutableListOf<(NodeStatus) -> Unit>()
    var current: NodeStatus = NodeStatus.Offline("fake")

    override fun addStatusListener(listener: (NodeStatus) -> Unit) {
        listeners += listener
    }

    override fun status(): NodeStatus = current

    fun emit(status: NodeStatus) {
        current = status
        listeners.forEach { it(status) }
    }
}
