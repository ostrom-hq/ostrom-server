// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.github.presentation

import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.presentation.GateScopes
import dev.ostrom.server.gates.presentation.requireAnyScope
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.kernel.auth.requireOrgPrincipal
import dev.ostrom.server.kernel.auth.requirePrincipal
import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.ApplicationCall
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
    /** Starts the install on GitHub. */
    @Serializable
    @Resource("install/start")
    class Start(val parent: GitHubAppPath = GitHubAppPath())

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
 * The GitHub routes of the contract: the webhook (signed, no credential), the install callback (its state is the
 * proof), and the organization's App status and install start (a browser session of an owner or admin).
 *
 * @param dependencies The use cases.
 */
fun Route.gitHubRoutes(dependencies: GitHubRoutesDependencies) = with(dependencies) {
    post<GitHubWebhookPath> {
        val body = call.receive<ByteArray>()
        if (!signature.verify(body, call.request.header(SIGNATURE_HEADER))) {
            throw OstromException(ErrorKind.UNAUTHORIZED, "This webhook is not signed by the GitHub App.")
        }
        webhooks(call.request.header(EVENT_HEADER), body.decodeToString())
        call.respond(HttpStatusCode.NoContent)
    }
    get<GitHubAppPath.Callback> { params ->
        installs.finish(params.installationId, params.code, params.state)
        call.respondRedirect("${settings.webOrigin}/console/settings?github=installed")
    }
    authenticated {
        get<GitHubAppPath> {
            val actor = actorOf(call, setOf(GateScopes.ALL_USER, ORGS_READ))
            val installation = repository.installation(actor.orgId)
            call.respond(
                GitHubAppStatus(
                    installed = installation != null,
                    slug = settings.slug,
                    installationId = installation?.installationId,
                    htmlUrl = settings.slug?.let { "${settings.webUrl}/apps/$it" },
                ),
            )
        }
        get<GitHubAppPath.Start> {
            val actor = actorOf(call, setOf(GateScopes.ALL_USER))
            val member = call.requireOrgPrincipal(members, actor.orgId)
            if (member.role != OrgRole.OWNER && member.role != OrgRole.ADMIN) {
                throw OstromException(ErrorKind.FORBIDDEN, "Only an owner or an admin installs the GitHub App.")
            }
            call.respondRedirect(installs.start(actor.orgId, actor.id))
        }
    }
}

private suspend fun GitHubRoutesDependencies.actorOf(call: ApplicationCall, accepted: Set<String>): GateActor {
    val principal = call.requirePrincipal()
    principal.requireAnyScope(accepted)
    return actors.resolve(principal)
        ?: throw OstromException(ErrorKind.FORBIDDEN, "This credential belongs to no organization.")
}

private const val SIGNATURE_HEADER = "X-Hub-Signature-256"
private const val EVENT_HEADER = "X-GitHub-Event"
private const val ORGS_READ = "orgs:read"
