package org.server.anonymous.business

/**
 * Manual dependency root (no DI framework — PATTERNS.md §5).
 * Phase 4 replaces the in-memory impls with real repositories; this class is the only place that changes.
 */
class AppGraph {
    val contactService: ContactService = InMemoryContactService()
    val messageService: MessageService = InMemoryMessageService()
}
