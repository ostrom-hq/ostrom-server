// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.github.domain

import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.kernel.settings.InstanceAuditRecord
import run.ostrom.server.kernel.settings.InstanceAuditTrail
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** A GitHub App entered by hand that the server refuses before asking GitHub (TeamCode's `invalid_github_app`). */
class InvalidGitHubAppException(message: String) :
    OstromException(ErrorKind.INVALID_REQUEST, message, "invalid_github_app")

/** Another save of the App came first (TeamCode's `github_app_conflict`): reload it before saving. */
class GitHubAppConflictException : OstromException(
    ErrorKind.CONFLICT,
    "The GitHub App changed. Reload it before saving.",
    "github_app_conflict",
)

/** GitHub does not accept the private key for this App ID (TeamCode's `github_app_rejected`). */
class GitHubAppRejectedException : OstromException(
    ErrorKind.UNPROCESSABLE,
    "GitHub did not accept the private key for this App ID. Check the App ID and the private key.",
    "github_app_rejected",
)

/** The key or the client ID is another App's (TeamCode's `github_app_mismatch`). */
class GitHubAppMismatchException : OstromException(
    ErrorKind.UNPROCESSABLE,
    "The private key or the client ID belongs to another GitHub App than this App ID.",
    "github_app_mismatch",
)

/**
 * The App as an administrator enters it (TeamCode's `SaveGitHubAppRequest`): a null secret keeps the stored one.
 *
 * @property appId The App ID.
 * @property clientId Its OAuth client ID.
 * @property clientSecret Its client secret, or null to keep it.
 * @property privateKey Its PEM private key, or null to keep it.
 * @property webhookSecret Its webhook secret, or null to keep it.
 * @property version The stored version it changes, or null for a first App.
 */
data class GitHubAppInput(
    val appId: Long,
    val clientId: String,
    val clientSecret: String?,
    val privateKey: String?,
    val webhookSecret: String?,
    val version: Int?,
) {
    // The key and the secrets never appear in a log or a dump.
    override fun toString(): String = "GitHubAppInput(appId=$appId, clientId=$clientId, version=$version)"
}

/**
 * The App as GitHub names it for a key (`GET /app` with the App's JWT).
 *
 * @property id Its App ID.
 * @property slug Its slug.
 * @property owner The account that owns it.
 * @property clientId Its OAuth client ID.
 */
data class VerifiedGitHubApp(val id: Long, val slug: String, val owner: String?, val clientId: String?)

/** GitHub's check of an App entered by hand. */
fun interface GitHubAppVerifier {
    /**
     * Asks GitHub which App a private key signs for.
     *
     * @param appId The App ID the JWT names.
     * @param privateKeyPem The key it is signed with.
     * @return The App, or null when GitHub does not accept the key for that id (or it is no RSA key).
     * @throws GitHubCallException When GitHub fails.
     */
    suspend fun verifyApp(appId: Long, privateKeyPem: String): VerifiedGitHubApp?
}

/**
 * The instance's App as its administrator reads it: never its key or secrets, only whether each is set.
 *
 * @property app The stored App, or null.
 * @property callbackUrls The callback URLs to register on the App.
 */
data class GitHubAppConfiguration(val app: GitHubAppCredentials?, val callbackUrls: List<String>)

/**
 * What a change of the App ends elsewhere: the links of a former client (removed, their tokens revoked by the former
 * client while its secret is known, as TeamCode's `saveApp`), and the installations of a former App id (kept, said
 * in a warning: they work again once each organization installs the new App).
 *
 * @param links The members' links.
 * @param github GitHub's OAuth side, which revokes.
 * @param installations The organizations' installations.
 * @param logger Where a change of the App id is said.
 */
class GitHubAppChange(
    private val links: GitHubLinkStore,
    private val github: GitHubUserAuthApi,
    private val installations: GitHubRepository,
    private val logger: Logger,
) {
    /**
     * Ends what the change makes stale.
     *
     * @param before The App stored before.
     * @param appId The App ID stored now.
     * @param clientId The client ID stored now.
     */
    suspend fun applied(before: GitHubAppCredentials, appId: Long, clientId: String) {
        // The links first: ending them matters more than the count the warning gives.
        val former = before.oauthApp()
        if (before.clientId != clientId) {
            val removed = links.removeLinksOfOtherClients(clientId)
            if (former != null) removed.forEach { GitHubLinks.revokeToken(github, former, it) }
        }
        if (before.appId != appId.toString()) {
            logger.warn(
                "The GitHub App ID changed: installations of the former App stop working until each organization " +
                    "installs the new App.",
                mapOf(
                    "formerAppId" to before.appId,
                    "appId" to appId,
                    "formerInstallations" to installations.installationCount(),
                ),
            )
        }
    }
}

/**
 * The instance's GitHub App entered by hand (TeamCode's `GET` and `PUT /api/v1/github/app`, `GitHubLinkService.app` and
 * `saveApp`), for an administrator of the instance signed in as a person. The App is checked with GitHub before it is
 * stored, in the one table the manifest flow fills (`github_apps`); every replica uses it within a minute.
 *
 * @param admins Who administers the instance.
 * @param store The stored App.
 * @param apps The App in use, forgotten after a save.
 * @param verifier GitHub's check.
 * @param change What a change ends elsewhere.
 * @param audit The instance's signed audit log.
 * @param callbackUrls The callback URLs to register on the App.
 * @param clock The clock.
 */
@Suppress("LongParameterList") // The use case and the collaborators of a save, each of its own module or port.
class GitHubAppConfigurations(
    private val admins: InstanceAdmins,
    private val store: GitHubAppStore,
    private val apps: GitHubAppSource,
    private val verifier: GitHubAppVerifier,
    private val change: GitHubAppChange,
    private val audit: InstanceAuditTrail,
    private val callbackUrls: List<String>,
    private val clock: Clock,
) {
    /**
     * Reads the App.
     *
     * @param principal The caller.
     * @return The App, without its key or secrets.
     * @throws NotAnInstanceAdminException When the caller is not an administrator of the instance signed in.
     */
    suspend fun read(principal: AuthenticatedPrincipal): GitHubAppConfiguration {
        requireAdmin(principal)
        return GitHubAppConfiguration(store.app(), callbackUrls)
    }

    /**
     * Enters the App, or changes it.
     *
     * @param principal The caller.
     * @param input The App.
     * @return The App saved, without its key or secrets.
     * @throws OstromException 403 for anyone but an administrator signed in, 400 `invalid_github_app`, 409
     *   `github_app_conflict`, 422 `github_app_rejected` or `github_app_mismatch`, 503 when GitHub does not answer.
     */
    suspend fun save(principal: AuthenticatedPrincipal, input: GitHubAppInput): GitHubAppConfiguration {
        requireAdmin(principal)
        validate(input)
        val current = store.app()
        if (current?.version != input.version) refuse(GitHubAppConflictException())
        // Another client needs its own secret; a kept secret that no longer opens is as good as none.
        val secret = input.clientSecret ?: current?.clientSecret?.takeIf { current.clientId == input.clientId }
            ?: refuse(InvalidGitHubAppException("Provide the client secret of the App."))
        val key = input.privateKey ?: current?.privateKeyPem
            ?: refuse(InvalidGitHubAppException("Provide the App's private key."))
        val verified = verify(input.appId, key)
        // GitHub names the App's client; an answer without one is checked on the id alone, as TeamCode does.
        val client = verified.clientId ?: input.clientId
        if (verified.id != input.appId || client != input.clientId) refuse(GitHubAppMismatchException())
        // Another App signs its deliveries with its own webhook secret: the former one is kept only for the same App.
        val webhook = input.webhookSecret ?: current?.webhookSecret?.takeIf { current.appId == input.appId.toString() }
        val app = CreatedGitHubApp(input.appId, verified.slug, verified.owner, key, webhook, input.clientId, secret)
        if (!store.replaceApp(app, input.version, principal.subjectId, clock.now())) {
            refuse(GitHubAppConflictException())
        }
        apps.forget()
        // Recorded as soon as the App is stored: what the change ends elsewhere may still fail after it.
        audit.record(
            InstanceAuditRecord(
                Uuid.parseOrNull(principal.subjectId),
                SAVED,
                "github_app",
                input.appId.toString(),
                mapOf("clientId" to input.clientId),
            ),
        )
        current?.let { change.applied(it, input.appId, input.clientId) }
        return GitHubAppConfiguration(store.app(), callbackUrls)
    }

    /**
     * Refuses anyone but an administrator of the instance signed in, before a body is read.
     *
     * @param principal The caller.
     * @throws NotAnInstanceAdminException Otherwise.
     */
    suspend fun requireAdmin(principal: AuthenticatedPrincipal) {
        if (!admins.isAdmin(principal)) refuse(NotAnInstanceAdminException())
    }

    private suspend fun verify(appId: Long, key: String): VerifiedGitHubApp = try {
        verifier.verifyApp(appId, key)
    } catch (error: CancellationException) {
        throw error
    } catch (_: GitHubCallException) {
        refuse(GitHubUnavailableException())
    } ?: refuse(GitHubAppRejectedException())

    private fun refuse(error: OstromException): Nothing = throw error

    /** The audit event and TeamCode's bounds. */
    companion object {
        /** The audit event of a save. */
        const val SAVED: String = "github.app.saved"

        /** The largest App ID. */
        const val MAX_APP_ID: Long = 999_999_999_999

        /** The shortest client secret. */
        const val MIN_SECRET: Int = 20

        /** The longest secret. */
        const val MAX_SECRET: Int = 256

        /** The longest private key. */
        const val MAX_KEY: Int = 16_384
    }
}

// A GitHub App's client ID: `Iv`, then 2 to 62 of these.
private val CLIENT_ID = Regex("Iv[0-9A-Za-z._-]{2,62}")

/**
 * TeamCode's `GitHubLinkService.validate`: what the server refuses before asking GitHub.
 *
 * @param input The App.
 * @throws InvalidGitHubAppException When a field is out of its bounds.
 */
internal fun validate(input: GitHubAppInput) {
    val key = input.privateKey
    val problem = when {
        input.appId !in 1..GitHubAppConfigurations.MAX_APP_ID -> "The App ID is a number from 1 to 999999999999."
        !CLIENT_ID.matches(input.clientId) -> "The client ID is a GitHub App's: it starts with Iv."
        !secretFits(input.clientSecret, GitHubAppConfigurations.MIN_SECRET) ->
            "The client secret has 20 to 256 characters, without spaces."
        !secretFits(input.webhookSecret, 1) -> "The webhook secret has 1 to 256 characters, without spaces."
        key != null && !pem(key) -> "The private key is the PEM file GitHub gave for the App."
        else -> null
    }
    if (problem != null) throw InvalidGitHubAppException(problem)
}

// Absent, or within the bounds and without a space or a control character.
private fun secretFits(secret: String?, min: Int): Boolean = secret == null ||
    secret.length in min..GitHubAppConfigurations.MAX_SECRET && secret.none { it.isWhitespace() || it.isISOControl() }

private fun pem(key: String): Boolean =
    key.length <= GitHubAppConfigurations.MAX_KEY && "-----BEGIN " in key && "PRIVATE KEY-----" in key
