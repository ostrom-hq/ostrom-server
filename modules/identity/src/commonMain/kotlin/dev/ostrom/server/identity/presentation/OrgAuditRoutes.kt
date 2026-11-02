// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.identity.presentation

import dev.ostrom.server.identity.domain.AuditIntegrity
import dev.ostrom.server.identity.domain.AuditReadForbiddenException
import dev.ostrom.server.identity.domain.ListOrgAuditUseCase
import dev.ostrom.server.identity.domain.OrgAuditLine
import dev.ostrom.server.identity.domain.OrgAuditPage
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.auth.PrincipalOrgResolver
import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.kernel.auth.requireOrgPrincipal
import dev.ostrom.server.kernel.auth.requireScope
import dev.ostrom.server.kernel.http.NullsKept
import io.ktor.resources.Resource
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.Serializable

/** The scope that reads an organization's audit log; a browser session holds it. */
const val AUDIT_READ_SCOPE = "audit:read"

/** What an event of the server itself names as its actor: not a login, which never holds a colon. */
const val SERVER_ACTOR = "server:ostrom"

/**
 * `GET /api/v1/audit` (`listAudit`): the audit log of the organization the request names (its only one, or the
 * `Ostrom-Org` header), newest first, 50 lines a page.
 *
 * @property actor Only this person's events, by login (a credential that is not a person's is not filtered on).
 * @property event Only the events of this name (`org.deleted`).
 * @property before The `next` of the previous page.
 */
@Serializable
@Resource("/api/v1/audit")
class OrgAuditApi(val actor: String? = null, val event: String? = null, val before: String? = null)

/**
 * A line of the audit log, the contract's `AuditEvent`, with what a verifier needs to check it: the canonical form
 * (`auditCanonicalForm`) is rebuilt from [id], [sequence], [event] (its `action`), [actorId], [meta] (its `details`),
 * [at], the organization, [previousHash] and [subject] (`targetType:targetId`, split at its first colon); its SHA-256
 * is [hash], [signature] verifies with the instance key [keyId], and [previousHash] is the [hash] of the line before.
 *
 * @property id The id of the signed event.
 * @property at When (ISO 8601).
 * @property event What happened.
 * @property actor Who: a person's login (their id once they no longer exist), `<kind>:<id>` for a credential that is
 *   not a person's (`service:…`, `agent:…`), [SERVER_ACTOR] for the server itself.
 * @property subject What it happened to, `type:id`.
 * @property meta Extra facts, never a secret; `credential` says how the person was signed in.
 * @property sequence The position in the organization's chain, from 1.
 * @property actorId The person's id as signed, null for a credential that is not a person's or the server.
 * @property previousHash The hash of the previous line of the chain, null for the first one.
 * @property hash The SHA-256 (hex) of the canonical form.
 * @property signature The instance's Ed25519 signature of the canonical form (base64).
 * @property keyId The instance key that signed it (`GET /api/v1/instance/keys`).
 * @property integrity What the server found when it read the line: `verified`, `altered` or `unlinked`.
 */
@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = OrgAuditEventResponse.Answer::class)
data class OrgAuditEventResponse(
    val id: String,
    val at: String,
    val event: String,
    val actor: String,
    val subject: String,
    val meta: Map<String, String>,
    val sequence: Long,
    val actorId: String?,
    val previousHash: String?,
    val hash: String,
    val signature: String,
    val keyId: String,
    val integrity: String,
) {
    /** Writes the fields the contract declares required and nullable as `null` when null. */
    object Answer : NullsKept<OrgAuditEventResponse>(generatedSerializer(), "actorId", "previousHash")
}

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
    actor(),
    "${event.entry.targetType}:${event.entry.targetId}",
    event.entry.details,
    event.sequence,
    event.entry.actorId?.toString(),
    event.previousHash,
    event.hash,
    event.signature,
    event.keyId,
    integrity.wireName(),
)

/** The contract's name of an integrity: `verified`, `altered`, `unlinked`. */
internal fun AuditIntegrity.wireName(): String = name.lowercase()

// The person's login, their id once they no longer exist, a credential that is not a person's by its kind and id (as
// IdentityOrgAuditTrail writes them), or the server.
private fun OrgAuditLine.actor(): String {
    val entry = event.entry
    val kind = entry.details["actor_kind"]
    return when {
        actorLogin != null -> actorLogin
        entry.actorId != null -> entry.actorId.toString()
        kind != null -> "$kind:${entry.details["actor_id"]}"
        else -> SERVER_ACTOR
    }
}
