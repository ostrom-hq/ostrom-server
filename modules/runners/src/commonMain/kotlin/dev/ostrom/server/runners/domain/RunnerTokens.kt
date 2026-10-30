package dev.ostrom.server.runners.domain

/**
 * Mints and hashes the runner tokens: one per execution, handed to the pod only, stored as a hash. A runner token
 * gives access to its own session's runner channel and terminal, nothing else.
 */
interface RunnerTokens {
    /**
     * Creates a new token.
     *
     * @return The token, `ostrom_runner_` followed by 64 hex characters of secure randomness.
     */
    fun mint(): String

    /**
     * Hashes a token for storage and lookup.
     *
     * @param token The token.
     *
     * @return The SHA-256 of the token, in lower-case hex.
     */
    fun hash(token: String): String
}

/** Months are counted in UTC, `YYYY-MM`. */
internal fun monthOf(instant: kotlin.time.Instant): String = instant.toString().substring(0, MONTH_LENGTH)

/** `YYYY-MM`. */
private const val MONTH_LENGTH = 7
