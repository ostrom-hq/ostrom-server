// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.integrations.github.presentation

import run.ostrom.server.integrations.github.domain.GitHubDeviceLinkProgress
import run.ostrom.server.integrations.github.domain.GitHubLinkActor
import run.ostrom.server.integrations.github.domain.GitHubLinkMemberNotFoundException
import run.ostrom.server.integrations.github.domain.GitHubLinkNotFoundException
import run.ostrom.server.integrations.github.domain.GitHubLinkPersonOnlyException
import run.ostrom.server.integrations.github.domain.GitHubLinkState
import run.ostrom.server.integrations.github.domain.GitHubLinkStatus
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.BrowserSessionRequiredException
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalChannel
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.TOKEN_AUTH
import run.ostrom.server.kernel.auth.authenticated
import run.ostrom.server.kernel.auth.outsideOrganization
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import kotlin.uuid.Uuid

/**
 * The members' GitHub link routes (`github.link.v1`). A person only (a browser session, the app, the CLI's login): a
 * service key, an agent or a device token is refused (403), whatever it holds. No route returns a GitHub token.
 *
 * - `GET`/`DELETE /api/v1/github/link`: the caller's own link; `POST …/device` and `POST …/device/{id}`: the device
 *   flow, for the CLI and the app.
 * - `GET /api/v1/github/link/start` (a browser session, started from the console) and the public
 *   `GET /api/v1/github/link/callback`, which sends the browser back to the console's `/github` page with `linked=1`
 *   or the refusal's `error=<error.type>` (an unforeseen failure stays a 500, logged as any other).
 * - `GET /api/v1/github/links`, `GET`/`DELETE /api/v1/github/links/{memberId}` and
 *   `GET /api/v1/github/links/{memberId}/start`: owners and admins (another member, unknown or malformed, 404).
 *
 * @param dependencies The links and the member resolver.
 */
@Suppress("LongMethod") // The ten routes of the links, one block each.
fun Route.gitHubLinkRoutes(dependencies: GitHubLinkRoutesDependencies) = with(dependencies) {
    // GitHub sends the browser back with its session cookie (SameSite=Lax): the session is read when there is one.
    authenticate(TOKEN_AUTH, optional = true) {
        get<GitHubLinkPath.Callback> { params ->
            val principal = call.principal<AuthenticatedPrincipal>()
            val outcome = try {
                links.completeWeb(params.state, params.code, params.error, principal?.let { sessionOf(it) to it })
                "linked=1"
            } catch (refused: OstromException) {
                "error=${refused.type}"
            }
            call.respondRedirect("$webOrigin/github?$outcome")
        }
    }
    authenticated {
        get<GitHubLinkPath> {
            call.respond(links.status(actorOf(call, write = false)).toDto())
        }
        delete<GitHubLinkPath> {
            links.unlink(actorOf(call, write = true))
            call.respond(HttpStatusCode.NoContent)
        }
        post<GitHubLinkPath.Device> {
            val link = links.startDevice(actorOf(call, write = true))
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(
                HttpStatusCode.Created,
                GitHubDeviceLinkDto(
                    link.id, link.userCode, link.verificationUri, link.expiresAt.toString(), link.intervalSeconds,
                ),
            )
        }
        post<GitHubLinkPath.Device.Id> { params ->
            val id = params.id.takeIf { ATTEMPT_ID.matches(it) } ?: throw GitHubLinkNotFoundException()
            val progress = links.pollDevice(actorOf(call, write = true), id)
            call.respond(
                when (progress) {
                    is GitHubDeviceLinkProgress.Pending -> GitHubDeviceLinkPollDto("pending", progress.intervalSeconds)
                    is GitHubDeviceLinkProgress.Linked ->
                        GitHubDeviceLinkPollDto("linked", link = progress.status.toDto())
                    GitHubDeviceLinkProgress.Expired -> GitHubDeviceLinkPollDto("expired")
                    GitHubDeviceLinkProgress.Denied -> GitHubDeviceLinkPollDto("denied")
                },
            )
        }
        get<GitHubLinkPath.Start> {
            call.respondRedirect(webStart(call, null))
        }
        get<GitHubLinksPath> {
            call.respond(GitHubLinksPage(links.links(actorOf(call, write = false)).map { it.toDto() }))
        }
        get<GitHubLinksPath.Member> { params ->
            call.respond(links.status(actorOf(call, write = false), memberId(params.memberId)).toDto())
        }
        delete<GitHubLinksPath.Member> { params ->
            links.unlink(actorOf(call, write = true), memberId(params.memberId))
            call.respond(HttpStatusCode.NoContent)
        }
        get<GitHubLinksPath.Member.Start> { params ->
            call.respondRedirect(webStart(call, memberId(params.parent.memberId)))
        }
    }
}

/**
 * A web start: a browser session that navigated from the console (a third-party page could open the link in a
 * signed-in browser), bound to that session.
 */
private suspend fun GitHubLinkRoutesDependencies.webStart(call: ApplicationCall, memberId: Uuid?): String {
    call.requireStartedHere(webOrigin)
    val actor = actorOf(call, write = true)
    if (actor.principal.channel != PrincipalChannel.BROWSER) throw BrowserSessionRequiredException()
    return links.startWeb(actor, sessionOf(actor.principal), memberId)
}

/**
 * The member a link request acts for: a person, in the organization the request names, with a scope that reaches
 * their cloud sessions or their link.
 */
@Suppress("ThrowsCount") // Who may link: a member, signed in for a write, with the right scope.
private suspend fun GitHubLinkRoutesDependencies.actorOf(call: ApplicationCall, write: Boolean): GitHubLinkActor {
    val principal = call.requirePrincipal()
    if (principal.kind != PrincipalKind.USER) throw GitHubLinkPersonOnlyException()
    if ((if (write) WRITE_SCOPES else READ_SCOPES).none { principal.allows(it) }) {
        throw OstromException(ErrorKind.FORBIDDEN, "This credential does not allow GitHub links.")
    }
    val member = members.resolve(principal, null) ?: throw principal.outsideOrganization()
    return GitHubLinkActor(member.orgId, member.memberId, member.role in ADMIN_ROLES, principal)
}

/** A malformed member id names nobody: 404, like an unknown one. */
private fun memberId(raw: String): Uuid = Uuid.parseOrNull(raw) ?: throw GitHubLinkMemberNotFoundException()

/** Maps a link to the contract. */
internal fun GitHubLinkStatus.toDto() = GitHubLinkDto(
    memberId = memberId.toString(),
    state = state?.wire ?: GitHubLinkState.NOT_LINKED,
    appConfigured = appConfigured,
    login = link?.identity?.login,
    userId = link?.identity?.userId,
    linkedAt = link?.linkedAt?.toString(),
    refreshedAt = link?.refreshedAt?.toString(),
    accessExpiresAt = link?.accessExpiresAt?.toString(),
)

// A link only serves the member's cloud sessions: the scopes of those sessions reach it (the CLI's login holds
// `cloud:write`, a browser session every scope of its person).
private val WRITE_SCOPES = listOf("cloud:write")
private val READ_SCOPES = listOf("cloud:write", "cloud:read")
private val ADMIN_ROLES = setOf(OrgRole.OWNER, OrgRole.ADMIN)
private val ATTEMPT_ID = Regex("[0-9a-f-]{36}")
