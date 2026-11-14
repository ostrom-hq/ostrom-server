// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.github.domain

import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.kernel.settings.InstanceAdministrators
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/** This instance already has a GitHub App (from the environment or created before): the manifest flow is closed. */
class GitHubAppExistsException : OstromException(ErrorKind.CONFLICT, "This instance already has a GitHub App.")

/** Only an administrator of the instance creates its GitHub App. */
class NotAnInstanceAdminException :
    OstromException(ErrorKind.FORBIDDEN, "Only an administrator of this instance creates its GitHub App.")

/** GitHub's side of the manifest flow. */
interface GitHubManifestApi {
    /**
     * Turns the code GitHub gave back into the App it created; GitHub answers it once, within an hour.
     *
     * @param code The code.
     * @return The App, or null when GitHub does not know the code.
     * @throws GitHubCallException When GitHub fails.
     */
    suspend fun convertManifest(code: String): CreatedGitHubApp?
}

/** Who administers the instance (not an organization: the App serves every organization of the instance). */
interface InstanceAdmins {
    /**
     * @param principal The caller.
     * @return Whether they administer the instance.
     */
    suspend fun isAdmin(principal: AuthenticatedPrincipal): Boolean
}

/**
 * [InstanceAdmins] on the instance's stored administrators (the admin module, Admin → Administrators in the console):
 * a person, never a token of an agent, a device or a service.
 *
 * @param administrators The instance's administrators.
 */
class StoredInstanceAdmins(private val administrators: InstanceAdministrators) : InstanceAdmins {
    override suspend fun isAdmin(principal: AuthenticatedPrincipal): Boolean {
        val person = Uuid.parseOrNull(principal.subjectId)?.takeIf { principal.kind == PrincipalKind.USER }
        return person != null && administrators.isAdmin(person)
    }
}

/**
 * The form that sends the manifest to GitHub: GitHub only takes it by a POST of the browser.
 *
 * @property action Where the form posts, with the state.
 * @property manifest The manifest, JSON.
 */
data class ManifestForm(val action: String, val manifest: String)

/**
 * Where the App created by the manifest sends people and events.
 *
 * @property publicUrl The API's public URL (`OSTROM_PUBLIC_URL`), for the callbacks and the webhook.
 * @property webOrigin The console (`OSTROM_WEB_ORIGIN`), the App's home page.
 * @property webUrl GitHub's web address.
 * @property appName The name of the App on GitHub.
 */
data class GitHubManifestTarget(
    val publicUrl: String,
    val webOrigin: String,
    val webUrl: String,
    val appName: String,
)

/**
 * Creates the instance's GitHub App by GitHub's manifest flow (`manifest.ts` of the prototype): an administrator of
 * the instance starts it, GitHub creates the App under their account (or an organization they name), then sends the
 * browser back with a code that [finish] exchanges for the App's id, key and secrets, stored sealed. The state of the
 * start is kept as its SHA-256 only, over the state and the session that started the flow, used once and for 10
 * minutes: GitHub sends the browser back with that session's cookie, and a state another session brings back matches
 * nothing (AUDIT-SECU-2 B2). Closed whenever an App exists: the environment's always wins ([GitHubAppSourceImpl]).
 *
 * The App asks what the integration uses and no more: `checks: write` for the `ostrom-gate` check; read-only
 * metadata, contents, pull requests, issues, actions and members (the directory and the analysis); the
 * `pull_request` events on `/webhooks/github`; and the person's authorization during the install
 * (`request_oauth_on_install`, S2-56), returning to the install callback. The members' cloud sessions clone and push
 * with their own user-to-server tokens, which never exceed the App's permissions: `contents` and `pull_requests` are
 * read and write for them; the server's own installation tokens keep asking for the read access they use.
 *
 * @param apps The App in use.
 * @param store The stored App and the states.
 * @param api GitHub.
 * @param admins Who may start it.
 * @param target Where the App points, read at each start (GitHub's address and the App's name are the console's).
 * @param clock The clock.
 */
class GitHubAppManifests(
    private val apps: GitHubAppSource,
    private val store: GitHubAppStore,
    private val api: GitHubManifestApi,
    private val admins: InstanceAdmins,
    private val target: () -> GitHubManifestTarget,
    private val clock: Clock,
) {
    /**
     * Starts the flow.
     *
     * @param principal The caller, a person signed in to the console.
     * @param owner The GitHub organization that will own the App, or null for the caller's own account.
     * @param session The session that starts it: only that session finishes it (AUDIT-SECU-2 B2).
     * @return The form to post to GitHub.
     * @throws NotAnInstanceAdminException When the caller does not administer the instance.
     * @throws GitHubAppExistsException When an App exists.
     * @throws OstromException 400 for an owner that is not a GitHub login.
     */
    suspend fun start(principal: AuthenticatedPrincipal, owner: String?, session: String): ManifestForm {
        when {
            !admins.isAdmin(principal) -> refuse(NotAnInstanceAdminException())
            apps.current().named -> refuse(GitHubAppExistsException())
            owner != null && !LOGIN.matches(owner) ->
                refuse(OstromException(ErrorKind.INVALID_REQUEST, "owner is not a GitHub organization."))
        }
        val state = Uuid.random().toHexString() + Uuid.random().toHexString()
        store.saveState(sha256Hex("$state:$session"), principal.subjectId, clock.now() + STATE_LIFETIME)
        val where = target()
        val page = if (owner == null) {
            "${where.webUrl}/settings/apps/new"
        } else {
            "${where.webUrl}/organizations/$owner/settings/apps/new"
        }
        return ManifestForm("$page?state=$state", manifest(where))
    }

    /**
     * Finishes the flow: GitHub came back with the code and the state.
     *
     * @param code GitHub's code.
     * @param state The state of [start].
     * @param session The session the browser came back with, or null without one.
     * @return The App, stored.
     * @throws OstromException 400 for a malformed callback or a code GitHub does not know, 401 without a session, 403
     *   for an unknown, used or expired state or one another session started, 409 when an App exists, 503 when GitHub
     *   does not answer.
     */
    suspend fun finish(code: String?, state: String?, session: String?): CreatedGitHubApp {
        val malformed = OstromException(ErrorKind.INVALID_REQUEST, "This is not a GitHub App creation callback.")
        if (code == null || !CODE.matches(code)) refuse(malformed)
        if (state.isNullOrEmpty() || state.length > MAX_STATE) refuse(malformed)
        if (session == null) {
            refuse(OstromException(ErrorKind.UNAUTHORIZED, "Sign in to the console, then create the App again."))
        }
        // A state brought back by another session (one that leaked through a proxy log) matches no row.
        val by = store.takeState(sha256Hex("$state:$session"), clock.now())
            ?: refuse(OstromException(ErrorKind.FORBIDDEN, "This link expired, was used or is another session's."))
        if (apps.current().named) refuse(GitHubAppExistsException())
        val app = convert(code)
            ?: refuse(OstromException(ErrorKind.INVALID_REQUEST, "GitHub does not know this code: start again."))
        if (!store.saveApp(app, by, clock.now())) refuse(GitHubAppExistsException())
        apps.forget()
        return app
    }

    private suspend fun convert(code: String): CreatedGitHubApp? = try {
        api.convertManifest(code)
    } catch (error: CancellationException) {
        throw error
    } catch (_: GitHubCallException) {
        refuse(GitHubUnavailableException())
    }

    private fun refuse(error: OstromException): Nothing = throw error

    private fun manifest(target: GitHubManifestTarget): String = buildJsonObject {
        val api = target.publicUrl
        put("name", target.appName)
        put("url", target.webOrigin)
        put("description", "Ostrom asks a person before an agent ships, and signs the answer.")
        put("redirect_url", "$api/api/v1/github/app/manifest/callback")
        putJsonArray("callback_urls") {
            add("$api/api/v1/github/app/install/callback")
            // The members' links of the cloud sessions (the web flow); Device Flow is an App setting no manifest sets.
            add("$api/api/v1/github/link/callback")
        }
        // Where GitHub returns after an install if the authorization on install is turned off.
        put("setup_url", "$api/api/v1/github/app/install/callback")
        put("request_oauth_on_install", true)
        // An install started again (GitHub's webhook was late) comes back to the callback after its settings are saved.
        put("setup_on_update", true)
        put("public", false)
        putJsonObject("hook_attributes") {
            put("url", "$api/webhooks/github")
            put("active", true)
        }
        putJsonObject("default_permissions") {
            for ((permission, access) in PERMISSIONS) put(permission, access)
        }
        putJsonArray("default_events") { add("pull_request") }
    }.toString()

    private companion object {
        const val MAX_STATE = 128
    }
}

/**
 * The SHA-256 of a text, in hexadecimal: how a single-use state is kept.
 *
 * @param text The text.
 * @return Its hash.
 */
internal suspend fun sha256Hex(text: String): String =
    CryptographyProvider.Default.get(SHA256).hasher().hash(text.encodeToByteArray())
        .joinToString("") { (it.toInt() and BYTE).toString(HEX).padStart(2, '0') }

private const val BYTE = 0xff
private const val HEX = 16

// Top-level, not in the private companion: a companion property has a getter nothing calls, which Kover counts.
private val STATE_LIFETIME = 10.minutes
private val LOGIN = Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}")
private val CODE = Regex("[A-Za-z0-9_-]{1,100}")
private val PERMISSIONS = listOf(
    "actions" to "read",
    "checks" to "write",
    "contents" to "write",
    "issues" to "read",
    "members" to "read",
    "metadata" to "read",
    "pull_requests" to "write",
)
