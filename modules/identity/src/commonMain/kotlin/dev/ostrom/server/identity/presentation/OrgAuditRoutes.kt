// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.identity.presentation

import dev.ostrom.server.identity.domain.AuditReadForbiddenException
import dev.ostrom.server.identity.domain.ListOrgAuditUseCase
import dev.ostrom.server.identity.domain.OrgAuditLine
import dev.ostrom.server.identity.domain.OrgAuditPage
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.auth.PrincipalOrgResolver
import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.kernel.auth.requireOrgPrincipal
import dev.ostrom.server.kernel.auth.requireScope
import io.ktor.resources.Resource
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable

/** The scope that reads an organization's audit log; a browser session holds it. */
const val AUDIT_READ_SCOPE = "audit:read"

/** What an event of the server itself names as its actor. */
const val SERVER_ACTOR = "ostrom"

/**
 * `GET /api/v1/audit` (`listAudit`): the audit log of the organization the request names (its only one, or the
 * `Ostrom-Org` header), newest first, 50 lines a page.
 *
 * @property actor Only this person's events, by login.
 * @property event Only the events of this name (`org.deleted`).
 * @property before The `next` of the previous page.
 */
@Serializable
@Resource("/api/v1/audit")
class OrgAuditApi(val actor: String? = null, val event: String? = null, val before: String? = null)

/**
 * A line of the audit log, the contract's `AuditEvent`.
 *
 * @property id The id of the signed event.
 * @property at When (ISO 8601).
 * @property event What happened.
 * @property actor Who: a login, [SERVER_ACTOR] for the server, or the id of a person who no longer exists.
 * @property subject What it happened to, `type:id`.
 * @property meta Extra facts, never a secret; `credential` says how the person was signed in.
 */
@Serializable
data class OrgAuditEventResponse(
    val id: String,
    val at: String,
    val event: String,
    val actor: String,
    val subject: String,
    val meta: Map<String, String>,
)

/**
 * A page of the audit log.
 *
 * @property items The lines, newest first.
 * @property next The `before` of the next page, absent on the last one.
 */
@Serializable
data class OrgAuditPageResponse(val items: List<OrgAuditEventResponse>, val next: String? = null)

/**
 * What the audit route needs.
 *
 * @property listOrgAudit Reads the log.
 * @property orgResolver The organization the request names, and the caller's role there.
 */
data class OrgAuditRoutesDependencies(val listOrgAudit: ListOrgAuditUseCase, val orgResolver: PrincipalOrgResolver)

/**
 * The audit route: an owner or an admin reads their organization's log, with a browser session or a token holding
 * [AUDIT_READ_SCOPE]. Another member gets a 403; a person outside the organization a 404, as for any of its routes.
 *
 * @param dependencies The use case and the resolver.
 */
fun Route.orgAuditRoutes(dependencies: OrgAuditRoutesDependencies) = with(dependencies) {
    authenticated {
        get<OrgAuditApi> { params ->
            call.requireScope(AUDIT_READ_SCOPE)
            val member = call.requireOrgPrincipal(orgResolver, null)
            if (member.role != OrgRole.OWNER && member.role != OrgRole.ADMIN) throw AuditReadForbiddenException()
            call.respond(listOrgAudit(member.orgId, params.actor, params.event, params.before).toResponse())
        }
    }
}

private fun OrgAuditPage.toResponse() = OrgAuditPageResponse(lines.map { it.toResponse() }, next)

private fun OrgAuditLine.toResponse() = OrgAuditEventResponse(
    event.id.toString(),
    event.occurredAt.toString(),
    event.entry.action,
    actorLogin ?: event.entry.actorId?.toString() ?: SERVER_ACTOR,
    "${event.entry.targetType}:${event.entry.targetId}",
    event.entry.details,
)
