// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.github.presentation

import run.ostrom.server.gates.domain.models.GateActor
import run.ostrom.server.gates.presentation.GateScopes
import run.ostrom.server.gates.presentation.requireAnyScope
import run.ostrom.server.integrations.github.domain.GitHubCallException
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.OrganizationNotFoundException
import run.ostrom.server.kernel.auth.TOKEN_AUTH
import run.ostrom.server.kernel.auth.authenticated
import run.ostrom.server.kernel.auth.outsideOrganization
import run.ostrom.server.kernel.auth.requireOrgPrincipal
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `/webhooks/github`: GitHub's deliveries, signed with the App's webhook secret (outside the API prefix). */
@Serializable
@Resource("/webhooks/github")
class GitHubWebhookPath

/** `/api/v1/github/app`: the organization's installation of the App. */
@Serializable
@Resource("/api/v1/github/app")
class GitHubAppPath {
    /**
     * Starts the install on GitHub.
     *
     * @property org The organization to install it on (its id or short name), checked like the `Ostrom-Org` header:
     *   the caller must be one of its owners or admins. Without it, the caller's organization.
     */
    @Serializable
    @Resource("install/start")
    class Start(val parent: GitHubAppPath = GitHubAppPath(), val org: String? = null)

    /**
     * GitHub's return from the install.
     *
     * @property installationId The installation.
     * @property code The authorization of the person who installed (the App asks for it during the install).
     * @property state The state of the start.
     */
    @Serializable
    @Resource("install/callback")
    class Callback(
        val parent: GitHubAppPath = GitHubAppPath(),
        @SerialName("installation_id") val installationId: String? = null,
        val code: String? = null,
        val state: String? = null,
    )
}

/**
 * The App and the organization's installation of it.
 *
 * @property installed Whether the organization installed it.
 * @property slug The App's slug.
 * @property installationId The installation.
 * @property htmlUrl The App's page on GitHub.
 */
@Serializable
data class GitHubAppStatus(
    val installed: Boolean,
    val slug: String? = null,
    val installationId: Long? = null,
    val htmlUrl: String? = null,
)

/**
 * The GitHub routes of the contract: the webhook (signed, no credential), the install callback (its state, bound to
 * the session that started it, is the proof), and the organization's App status and install start (a browser session
 * of an owner or admin). The callback always sends the browser back to the console's `/github` page, with
 * `installed=1` or `error=<error.type>`: never free text in the URL.
 *
 * @param dependencies The use cases.
 */
fun Route.gitHubRoutes(dependencies: GitHubRoutesDependencies) = with(dependencies) {
    post<GitHubWebhookPath> {
        val body = call.receive<ByteArray>()
        if (!signature.verify(body, call.request.header(SIGNATURE_HEADER))) {
            throw OstromException(ErrorKind.UNAUTHORIZED, "This webhook is not signed by the GitHub App.")
        }
        webhooks(call.request.header(EVENT_HEADER), call.request.header(DELIVERY_HEADER), body.decodeToString())
        call.respond(HttpStatusCode.NoContent)
    }
    // GitHub sends the browser back with its session cookie (SameSite=Lax): the session is read when there is one.
    authenticate(TOKEN_AUTH, optional = true) {
        get<GitHubAppPath.Callback> { params ->
            val session = call.principal<AuthenticatedPrincipal>()?.let(::sessionOf)
            val outcome = try {
                installs.finish(params.installationId, params.code, params.state, session)
                "installed=1"
            } catch (refused: OstromException) {
                "error=${refused.type}"
            } catch (_: GitHubCallException) {
                "error=${ErrorKind.UNAVAILABLE.type}"
            }
            call.respondRedirect("${settings.webOrigin}/github?$outcome")
        }
    }
    authenticated {
        get<GitHubAppPath> {
            val actor = actorOf(call, setOf(GateScopes.ALL_USER, ORGS_READ))
            val installation = repository.installation(actor.orgId)
            // The environment's App, or the one the manifest flow created.
            val slug = apps.current().slug
            call.respond(
                GitHubAppStatus(
                    installed = installation != null,
                    slug = slug,
                    installationId = installation?.installationId,
                    htmlUrl = slug?.let { "${settings.webUrl}/apps/$it" },
                ),
            )
        }
        get<GitHubAppPath.Start> { params ->
            call.requireStartedHere(settings.webOrigin)
            val actor = actorOf(call, setOf(GateScopes.ALL_USER))
            val principal = call.requirePrincipal()
            // A named organization the caller is not in, or that does not exist, is not found (404, as the contract
            // says); a member whose role is too low is refused (403).
            val orgId = if (params.org == null) actor.orgId else orgs.orgId(params.org)
            val member = call.requireOrgPrincipal(members, orgId ?: throw OrganizationNotFoundException())
            if (member.role != OrgRole.OWNER && member.role != OrgRole.ADMIN) {
                throw OstromException(ErrorKind.FORBIDDEN, "Only an owner or an admin installs the GitHub App.")
            }
            call.respondRedirect(installs.start(member.orgId, actor.id, sessionOf(principal)))
        }
    }
}

private suspend fun GitHubRoutesDependencies.actorOf(call: ApplicationCall, accepted: Set<String>): GateActor {
    val principal = call.requirePrincipal()
    principal.requireAnyScope(accepted)
    return actors.resolve(principal)
        ?: throw principal.outsideOrganization()
}

private const val SIGNATURE_HEADER = "X-Hub-Signature-256"
private const val EVENT_HEADER = "X-GitHub-Event"
private const val DELIVERY_HEADER = "X-GitHub-Delivery"
private const val ORGS_READ = "orgs:read"
