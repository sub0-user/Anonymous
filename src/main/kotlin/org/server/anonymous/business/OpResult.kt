package org.server.anonymous.business

/** Standard result type for business operations (see doc/rules/PATTERNS.md §3). */
sealed interface OpResult<out T> {
    data class Success<T>(
        val value: T,
    ) : OpResult<T>

    data class Failure(
        val reason: String,
    ) : OpResult<Nothing>
}
