// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.orgs.presentation

import run.ostrom.server.identity.domain.isBrowserSession
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.kernel.auth.ServiceKeyScopes
import run.ostrom.server.kernel.auth.authenticated
import run.ostrom.server.kernel.auth.requireOrgPrincipal
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.kernel.auth.requireRecentSignIn
import run.ostrom.server.kernel.auth.requireScope
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.orgs.domain.InvalidOrgRequestException
import run.ostrom.server.orgs.domain.Invitation
import run.ostrom.server.orgs.domain.InvitationNotFoundException
import run.ostrom.server.orgs.domain.InviteRequest
import run.ostrom.server.orgs.domain.MAX_MEMBER_PAGE
import run.ostrom.server.orgs.domain.MemberProfile
import run.ostrom.server.orgs.domain.MemberWrite
import run.ostrom.server.orgs.domain.Membership
import run.ostrom.server.orgs.domain.OrgChange
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.patch
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlin.uuid.Uuid

/** The scope that reads organizations and members. */
const val ORGS_READ = "orgs:read"

/** The scope that changes them. */
const val ORGS_WRITE = "orgs:write"

/**
 * The organization routes. Every org route first resolves the caller as a member of the
 * organization it names (a 404 otherwise, for a missing organization as for someone else's), then
 * the use case checks the caller's role. Deleting an organization and transferring its ownership
 * take a browser session: never a token; deleting it also takes a sign-in of less than 5 minutes.
 *
 * @param dependencies The use cases.
 */
fun Route.orgsRoutes(dependencies: OrgsRoutesDependencies) =
    with(dependencies) {
        post<InvitationLinksApi.Preview, InvitationTokenRequest> { _, request ->
            val (org, invitation) = invitations.preview(request.token)
            call.respond(
                InvitationPreviewResponse(
                    org.name,
                    org.slug,
                    invitation.role.key(),
                    invitation.email,
                    invitation.expiresAt.toString(),
                )
            )
        }
        authenticated {
            get<OrgsApi> {
                val userId = call.requireScope(ORGS_READ).userId()
                call.respond(OrgsResponse(orgs.listMine(userId).map { it.toOrgResponse() }))
            }
            post<OrgsApi, CreateOrgRequest> { _, request ->
                val userId = call.requireScope(ORGS_WRITE).userId()
                call.respond(
                    HttpStatusCode.Created,
                    orgs.create(userId, request.name, request.slug).toOrgResponse(),
                )
            }
            post<InvitationLinksApi.Accept, InvitationTokenRequest> { _, request ->
                val userId = call.requireScope(ORGS_WRITE).userId()
                call.respond(invitations.accept(userId, request.token).toOrgResponse())
            }
            get<OrgsApi.Org> { params ->
                val (orgId, userId) = member(call, params.org, ORGS_READ)
                call.respond(orgs.get(orgId, userId).toOrgResponse())
            }
            patch<OrgsApi.Org, UpdateOrgRequest> { params, request ->
                val (orgId, userId) = member(call, params.org, ORGS_WRITE)
                val change = OrgChange(request.name, request.timeZone, request.slug, request.hooksDeadlineSeconds)
                call.respond(orgs.update(orgId, userId, change).toOrgResponse())
            }
            delete<OrgsApi.Org> { params ->
                val (orgId, userId) = member(call, params.org, ORGS_WRITE, browserOnly = true)
                val confirm = call.receive<DeleteOrgRequest>().confirm
                orgs.delete(orgId, userId, confirm, call.requirePrincipal().authenticatedAt)
                call.respond(HttpStatusCode.NoContent)
            }
            post<OrgsApi.Org.Transfer, TransferRequest> { params, request ->
                val (orgId, userId) = member(call, params.parent.org, ORGS_WRITE, browserOnly = true)
                call.requireRecentSignIn("transfer the organization")
                orgs.transfer(orgId, userId, request.login)
                call.respond(HttpStatusCode.NoContent)
            }
            post<OrgsApi.Org.Leave> { params ->
                val (orgId, userId) = member(call, params.parent.org, ORGS_WRITE)
                members.leave(orgId, userId)
                call.respond(HttpStatusCode.NoContent)
            }
            memberRoutes(dependencies)
            invitationRoutes(dependencies)
        }
    }

private fun Route.memberRoutes(dependencies: OrgsRoutesDependencies) =
    with(dependencies) {
        get<OrgsApi.Org.Members> { params ->
            // The member directory is one of the reads a `team:read` service key makes, as its creator, an admin.
            val (orgId, userId) = member(call, params.parent.org, ORGS_READ, teamRead = true)
            val after = params.after?.let {
                Uuid.parseOrNull(it) ?: throw InvalidOrgRequestException("after is a member id.")
            }
            val page = members.list(orgId, userId, after, params.limit ?: MAX_MEMBER_PAGE, params.q)
            call.respond(MembersResponse(page.items.map { it.toMemberResponse() }, page.next?.toString()))
        }
        get<OrgsApi.Org.Members.Login> { params ->
            val (orgId, userId) = member(call, params.parent.parent.org, ORGS_READ)
            call.respond(members.get(orgId, userId, params.login).toMemberResponse())
        }
        patch<OrgsApi.Org.Members.Login, UpdateMemberRequest> { params, request ->
            val (orgId, userId) = member(call, params.parent.parent.org, ORGS_WRITE)
            val role = roleOf(request.role)
            if (heldForConsole(call, orgId, userId, MemberWrite.Role(params.login, role))) return@patch
            call.requireRecentSignIn("change a member's role")
            call.respond(members.changeRole(orgId, userId, params.login, role).toMemberResponse())
        }
        delete<OrgsApi.Org.Members.Login> { params ->
            val (orgId, userId) = member(call, params.parent.parent.org, ORGS_WRITE)
            if (heldForConsole(call, orgId, userId, MemberWrite.Removal(params.login))) return@delete
            call.requireRecentSignIn("remove a member")
            members.remove(orgId, userId, params.login)
            call.respond(HttpStatusCode.NoContent)
        }
    }

private fun Route.invitationRoutes(dependencies: OrgsRoutesDependencies) =
    with(dependencies) {
        post<OrgsApi.Org.Members.Invites, InviteRequestBody> { params, request ->
            val (orgId, userId) = member(call, params.parent.parent.org, ORGS_WRITE)
            val invites =
                request.invites.map { invite ->
                    val email =
                        invite["email"]
                            ?: throw InvalidOrgRequestException("Each invite needs an email.")
                    InviteRequest(email, roleOf(invite["role"] ?: "member"))
                }
            if (heldForConsole(call, orgId, userId, MemberWrite.Invites(invites))) return@post
            // An invitation adds a member, with any role: the same fresh sign-in as changing one.
            call.requireRecentSignIn("invite a member")
            call.respond(InvitedResponse(invitations.byEmail(orgId, userId, invites)))
        }
        get<OrgsApi.Org.Invitations> { params ->
            val (orgId, userId) = member(call, params.parent.org, ORGS_READ)
            call.respond(
                InvitationsResponse(
                    invitations.list(orgId, userId).map { it.toInvitationResponse() }
                )
            )
        }
        post<OrgsApi.Org.Invitations.Links, CreateLinkRequest> { params, request ->
            val (orgId, userId) = member(call, params.parent.parent.org, ORGS_WRITE)
            val role = roleOf(request.role)
            if (heldForConsole(call, orgId, userId, MemberWrite.Link(role, request.maxUses))) return@post
            call.requireRecentSignIn("invite a member")
            val issued = invitations.link(orgId, userId, role, request.maxUses)
            call.respond(
                HttpStatusCode.Created,
                InvitationLinkResponse(issued.invitation.toInvitationResponse(), issued.url),
            )
        }
        delete<OrgsApi.Org.Invitations.Id> { params ->
            val (orgId, userId) = member(call, params.parent.parent.org, ORGS_WRITE)
            invitations.revoke(
                orgId,
                userId,
                Uuid.parseOrNull(params.id) ?: throw InvitationNotFoundException(),
            )
            call.respond(HttpStatusCode.NoContent)
        }
    }

/**
 * Resolves the caller as a member of the organization [ref] names, with [scope]; the token's
 * organization binding applies ([PrincipalOrgResolver]).
 *
 * @param call The request.
 * @param ref The organization's id or short name.
 * @param scope The scope the route needs.
 * @param browserOnly Whether the route refuses tokens.
 * @param teamRead Whether a service key holding `team:read` passes too, as the person who created it.
 * @return The organization id and the caller's user id.
 */
private suspend fun OrgsRoutesDependencies.member(
    call: ApplicationCall,
    ref: String,
    scope: String,
    browserOnly: Boolean = false,
    teamRead: Boolean = false,
): Pair<Uuid, Uuid> {
    // Only a service key can hold `team:read`: a person's scopes never list it.
    val principal = call.requirePrincipal().takeIf { teamRead && ServiceKeyScopes.TEAM_READ in it.scopes }
        ?: call.requireScope(scope)
    val userId = principal.userId()
    if (browserOnly && !principal.isBrowserSession) {
        throw OstromException(
            ErrorKind.FORBIDDEN,
            "This action needs a signed-in browser session, not a token.",
        )
    }
    return call.requireOrgPrincipal(resolver, orgs.find(ref)).orgId to userId
}

/**
 * The user id of a person's principal.
 *
 * @return The id.
 * @throws OstromException A 403 for an agent, a device or a service: organizations are managed by
 *   people.
 */
internal fun AuthenticatedPrincipal.userId(): Uuid {
    // A service key of member scopes acts as the person who created it; any other key or agent is nobody here.
    val service = kind == PrincipalKind.SERVICE
    val person = if (service) onBehalfOf else subjectId
    val id = Uuid.parseOrNull(person.orEmpty()).takeIf { service || kind == PrincipalKind.USER }
    return id
        ?: throw OstromException(
            ErrorKind.FORBIDDEN,
            "Organizations are managed by people, not by agents.",
        )
}

/**
 * Reads a role named in a request.
 *
 * @param key `owner`, `admin`, `member`, `approver` or `billing`.
 * @return The role.
 * @throws InvalidOrgRequestException For any other value.
 */
internal fun roleOf(key: String): OrgRole =
    OrgRole.entries.firstOrNull { it.key() == key }
        ?: throw InvalidOrgRequestException(
            "The role must be one of owner, admin, member, approver, billing."
        )

/**
 * The API name of a role.
 *
 * @return `owner`, `admin`…
 */
internal fun OrgRole.key(): String = name.lowercase()

private fun Membership.toOrgResponse() =
    OrgResponse(
        org.id.toString(),
        org.slug,
        org.name,
        member.role.key(),
        org.createdAt.toString(),
        org.timeZone,
        org.hooksDeadlineSeconds,
    )

internal fun MemberProfile.toMemberResponse() =
    MemberResponse(
        member.id.toString(),
        login,
        displayName,
        avatarUrl.orEmpty(),
        email,
        member.role.key(),
        member.joinedAt.toString(),
    )

internal fun Invitation.toInvitationResponse() =
    InvitationResponse(
        id.toString(),
        email,
        role.key(),
        createdAt.toString(),
        expiresAt.toString(),
        maxUses,
        uses,
    )
