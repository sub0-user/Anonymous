package org.server.anonymous.business

/**
 * v3 onion addresses are 56 characters from the base32 alphabet `a-z2-7`,
 * followed by `.onion`.
 */
object OnionAddressValidator {
    val V3_REGEX: Regex = Regex("^[a-z2-7]{56}\\.onion$")

    fun isValid(value: String): Boolean = V3_REGEX.matches(value)
}
