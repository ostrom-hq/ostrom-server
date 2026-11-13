// SPDX-License-Identifier: Apache-2.0
package run.ostrom.runner

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The GitHub user token of the member the execution runs for. The token never appears in `toString`.
 *
 * @property token The user-to-server token.
 * @property login The linked account, if the server said it.
 * @property expiresAt When it expires, null when unknown.
 */
class GitHubToken(val token: String, val login: String?, val expiresAt: Instant?) {
    override fun toString(): String = "GitHubToken(login=$login, expiresAt=$expiresAt)"
}

/**
 * What `git` asks a credential helper for: the remote it is about to contact.
 *
 * @property protocol The remote's protocol (`https`).
 * @property host The remote's host.
 * @property path The repository's path, when git sends it.
 */
@Serializable
data class CredentialRequest(val protocol: String? = null, val host: String? = null, val path: String? = null)

/**
 * A username and secret for one remote. The secret never appears in `toString`.
 *
 * @property username The username.
 * @property password The secret.
 */
@Serializable
data class Credential(val username: String, val password: String) {
    override fun toString(): String = "Credential(username=$username)"
}

/**
 * The GitHub credential of `git` and `gh` in the pod: the token of the member the execution runs for, asked of the
 * server (`POST /api/v1/cloud/runner/github`) when first needed and again [RENEW_MARGIN] before it expires, kept in
 * the runner's memory only. It answers for `https://github.com` and nothing else, so no other remote ever receives
 * it; without a usable link it answers nothing (public repositories clone anonymously) and the server is not asked
 * again for [RETRY].
 *
 * @param fetch Asks the server; null without a usable link. A failure throws (GitHub unreachable included).
 * @param clock The time.
 */
class GitHubCredentialSource(private val fetch: suspend () -> GitHubToken?, private val clock: Clock) {
    private val lock = Mutex()
    private var current: GitHubToken? = null
    private var renewAt: Instant? = null

    /**
     * The credential for a remote.
     *
     * @param request What git describes.
     *
     * @return `x-access-token` and the token for `https://github.com`, null for anything else or without a link.
     */
    suspend fun credential(request: CredentialRequest): Credential? {
        if (request.protocol != "https" || request.host != HOST) return null
        return token()?.let { Credential(USERNAME, it.token) }
    }

    /**
     * @return The current token, asking the server only on the first call or once the last answer is due; null
     *   without a link or once the token expired.
     *
     * @throws Exception When the server could not answer: it is asked again after [RETRY], and the last token is
     *   still handed out until it expires.
     */
    suspend fun token(): GitHubToken? = lock.withLock {
        val now = clock.now()
        if (renewAt.let { it == null || now >= it }) {
            // A failure is retried after a while, not at every git call.
            renewAt = now + RETRY
            val fresh = fetch()
            current = fresh
            val due = when {
                fresh == null -> now + RETRY
                fresh.expiresAt == null -> now + UNKNOWN_EXPIRY
                else -> fresh.expiresAt - RENEW_MARGIN
            }
            // A token that expires soon is not asked for again at every call.
            renewAt = maxOf(due, now + RETRY)
        }
        current?.takeIf { token -> token.expiresAt.let { it == null || it > now } }
    }

    /** @return When [token] next asks the server; now before the first call. */
    suspend fun renewsAt(): Instant = lock.withLock { renewAt ?: clock.now() }

    /** Constants of the enclosing type. */
    companion object {
        /** The one host the token is for. */
        const val HOST = "github.com"

        /** The username GitHub expects with a token. */
        const val USERNAME = "x-access-token"

        /** How long before its expiry a token is asked for again. */
        private val RENEW_MARGIN: Duration = 5.minutes

        /** How long a token whose expiry the server did not say is kept. */
        private val UNKNOWN_EXPIRY: Duration = 5.minutes

        /** How long after a refusal (or a token about to expire) the server is not asked again. */
        val RETRY: Duration = 30.seconds
    }
}
