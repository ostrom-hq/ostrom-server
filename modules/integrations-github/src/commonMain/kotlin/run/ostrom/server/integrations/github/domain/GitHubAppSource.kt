// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.github.domain

import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The GitHub App of the instance: created by the manifest flow (or imported once from the former variables) and
 * stored sealed.
 *
 * @property appId Its id.
 * @property slug Its slug, for the install link.
 * @property privateKeyPem Its private key.
 * @property webhookSecret The secret GitHub signs its webhooks with.
 * @property clientId Its OAuth client id.
 * @property clientSecret Its OAuth client secret.
 */
data class GitHubAppCredentials(
    val appId: String? = null,
    val slug: String? = null,
    val privateKeyPem: String? = null,
    val webhookSecret: String? = null,
    val clientId: String? = null,
    val clientSecret: String? = null,
) {
    /** Whether an App is named at all: an id or a slug. */
    val named: Boolean get() = appId != null || slug != null

    /** The slug to install the App with, only when the server can check who installs it (its OAuth client). */
    val installSlug: String? get() = if (clientId == null || clientSecret == null) null else slug

    // The key and the secrets never appear in a log or a dump.
    override fun toString(): String = "GitHubAppCredentials(appId=$appId, slug=$slug)"
}

/**
 * An App GitHub just created from the manifest (`POST /app-manifests/{code}/conversions`).
 *
 * @property id Its id.
 * @property slug Its slug.
 * @property owner The account that owns it.
 * @property privateKeyPem Its private key.
 * @property webhookSecret Its webhook secret.
 * @property clientId Its OAuth client id (null only for an App imported from variables without one).
 * @property clientSecret Its OAuth client secret (null likewise).
 */
data class CreatedGitHubApp(
    val id: Long,
    val slug: String,
    val owner: String?,
    val privateKeyPem: String,
    val webhookSecret: String?,
    val clientId: String?,
    val clientSecret: String?,
) {
    // The key and the secrets never appear in a log or a dump.
    override fun toString(): String = "CreatedGitHubApp(id=$id, slug=$slug, owner=$owner)"
}

/** The App the integration uses now. */
interface GitHubAppSource {
    /** @return The App, every field null when there is none. */
    suspend fun current(): GitHubAppCredentials

    /** Drops what was read, after the App is stored. */
    fun forget()
}

/** The App created by the manifest flow, and the single-use states of that flow. Instance rows, not an org's. */
interface GitHubAppStore {
    /** @return The stored App, opened, or null. */
    suspend fun app(): GitHubAppCredentials?

    /**
     * Stores the App, its key and secrets sealed.
     *
     * @param app The App.
     * @param by Who created it.
     * @param at When.
     * @return False when an App was already stored: the first one stays.
     */
    suspend fun saveApp(app: CreatedGitHubApp, by: String, at: Instant): Boolean

    /**
     * Stores the hash of a manifest state.
     *
     * @param stateHash The hash of the state sent to GitHub.
     * @param by Who started the flow.
     * @param expiresAt When it stops being accepted.
     */
    suspend fun saveState(stateHash: String, by: String, expiresAt: Instant)

    /**
     * Uses a manifest state once: it is deleted, and returned only if not expired.
     *
     * @param stateHash The hash of the state GitHub sent back.
     * @param now The current time.
     * @return Who started the flow, or null.
     */
    suspend fun takeState(stateHash: String, now: Instant): String?
}

/**
 * [GitHubAppSource]: the stored App, read at most every [STORED_FOR] (a server that did not create it sees it within
 * that time), opened only by the store. The former variables are only imported once ([ImportGitHubAppUseCase]).
 *
 * @param store The stored App.
 * @param clock The clock.
 */
class GitHubAppSourceImpl(
    private val store: GitHubAppStore,
    private val clock: Clock,
) : GitHubAppSource {
    private var held: Pair<Instant, GitHubAppCredentials?>? = null

    override suspend fun current(): GitHubAppCredentials {
        val now = clock.now()
        val fresh = held?.takeIf { now - it.first < STORED_FOR }
        val stored = if (fresh != null) fresh.second else store.app().also { held = now to it }
        return stored ?: GitHubAppCredentials()
    }

    override fun forget() {
        held = null
    }
}

// Top-level, not in a companion: a companion property has a getter nothing calls, which Kover counts.
private val STORED_FOR = 60.seconds
