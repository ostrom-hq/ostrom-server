// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.budgets.presentation

import run.ostrom.server.budgets.domain.BudgetOverride
import run.ostrom.server.budgets.domain.BudgetOverrideGrant
import run.ostrom.server.budgets.domain.NamedBudgetOverrides
import run.ostrom.server.budgets.domain.InvalidBudgetOverrideException
import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.auditDetails
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.kernel.auth.requireRecentSignIn
import run.ostrom.server.secrets.domain.Caller
import run.ostrom.server.secrets.domain.CallerResolver
import run.ostrom.server.secrets.domain.ScopeRequiredException
import run.ostrom.server.secrets.presentation.requireCaller
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlin.uuid.Uuid

/**
 * The routes of members' temporary daily budgets (TeamCode's `/members/{id}/budget-overrides`), inside an
 * authenticated route: owners and admins, a person (a service key is refused even with the scope, and so are agent and
 * device tokens); a grant or a revocation from the console needs a sign-in less than five minutes old. Each change is
 * written to the organization's audit log (`budget_override.granted`, `.superseded`, `.revoked`), its subject
 * `login/id`.
 *
 * @param dependencies The use cases.
 */
fun Route.budgetOverrideRoutes(dependencies: BudgetsRoutesDependencies) = with(dependencies) {
    get<BudgetOverridesApi> { resource ->
        val caller = call.requirePerson(callers, BudgetScopes.READ)
        val page = listOverrides(caller, resource.login, resource.before)
        call.respond(
            BudgetOverridesPage(page.toDtos(), page.next?.toString(), page.activeId?.toString()),
        )
    }
    post<BudgetOverridesApi> { resource ->
        val caller = call.requirePerson(callers, BudgetScopes.WRITE)
        // A member is refused before being asked to sign in again.
        caller.requireAdmin()
        call.requireRecentSignIn("grant a temporary budget")
        val principal = call.requirePrincipal()
        val request = call.receive<GrantBudgetOverrideRequest>()
        val replaces = request.replacesId?.let {
            Uuid.parseOrNull(it) ?: throw InvalidBudgetOverrideException("replacesId is an override's id.")
        }
        val grant = BudgetOverrideGrant(request.micros, request.durationMinutes, request.reason, replaces)
        val granted = grantOverride(caller, resource.login, grant)
        val override = granted.items.single()
        granted.superseded?.let {
            audit.record(principal.event(caller, "budget_override.superseded", resource.login, it))
        }
        audit.record(principal.event(caller, "budget_override.granted", resource.login, override.id, override))
        call.respond(HttpStatusCode.Created, granted.toDtos().single())
    }
    post<BudgetOverridesApi.Revoke> { resource ->
        val caller = call.requirePerson(callers, BudgetScopes.WRITE)
        caller.requireAdmin()
        call.requireRecentSignIn("revoke a temporary budget")
        val principal = call.requirePrincipal()
        val request = call.receive<RevokeBudgetOverrideRequest>()
        val login = resource.parent.login
        val revoked = revokeOverride(caller, login, resource.id, request.reason)
        val override = revoked.items.single()
        if (revoked.changed) audit.record(principal.event(caller, "budget_override.revoked", login, override.id))
        call.respond(revoked.toDtos().single())
    }
}

/** A person's call: a service key is refused even when it holds the scope ([requireCaller] refuses agents, devices). */
private suspend fun ApplicationCall.requirePerson(callers: CallerResolver, scope: String): Caller {
    if (requirePrincipal().kind == PrincipalKind.SERVICE) throw ScopeRequiredException("a person's sign-in")
    return requireCaller(callers, scope)
}

private fun AuthenticatedPrincipal.event(
    caller: Caller,
    event: String,
    login: String,
    id: Uuid,
    override: BudgetOverride? = null,
) = OrgAuditRecord(
    caller.orgId,
    this,
    event,
    "budget_override",
    "$login/$id",
    auditDetails("micros" to override?.micros, "expires_at" to override?.expiresAt),
)

/** The overrides, each person named by login (by id when they left the org). */
private fun NamedBudgetOverrides.toDtos(): List<BudgetOverrideDto> = items.map { it.toDto(logins) }

private fun BudgetOverride.toDto(logins: Map<Uuid, String>): BudgetOverrideDto {
    fun name(id: Uuid) = logins[id] ?: id.toString()
    return BudgetOverrideDto(
        id = id.toString(),
        member = name(memberId),
        micros = micros,
        createdAt = createdAt.toString(),
        expiresAt = expiresAt.toString(),
        reason = reason,
        createdBy = name(createdBy),
        replacesId = replacesId?.toString(),
        revokedAt = revokedAt?.toString(),
        revokedBy = revokedBy?.let(::name),
        revocationReason = revocationReason,
    )
}
