// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules.presentation

import dev.ostrom.server.gates.domain.services.GateActorResolver
import dev.ostrom.server.gates.presentation.GateScopes
import dev.ostrom.server.kernel.auth.GovernanceScopes
import dev.ostrom.server.kernel.auth.WriteAccess
import dev.ostrom.server.kernel.auth.auditGovernance
import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.kernel.auth.governs
import dev.ostrom.server.kernel.auth.requirePrincipal
import dev.ostrom.server.rules.domain.models.Decider
import dev.ostrom.server.rules.domain.models.DecisionQuery
import dev.ostrom.server.rules.domain.models.OnlyConsoleDecidesException
import dev.ostrom.server.secrets.domain.CallerResolver
import dev.ostrom.server.secrets.presentation.requireCaller
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.patch
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.json.JsonObject

/**
 * The rules routes. A member, or a token with [RuleScopes.READ], reads the rules and the decisions (the policy too, or
 * with `gates:read`); only an owner or admin writes, decides or deletes a rule and sets the policy (CONVENTIONS 16.2),
 * signed in to the console or with the token of `ostrom login --admin` ([GovernanceScopes.POLICY_WRITE] for the rules,
 * [GovernanceScopes.GATES_POLICY] for the policy, bound to their organization): any other token is refused even with
 * every scope, and a device always is.
 *
 * @param dependencies The use cases.
 */
fun Route.rulesRoutes(dependencies: RulesRoutesDependencies) = with(dependencies) {
    authenticated {
        get<RulesApi> { filters ->
            val caller = call.requireCaller(callers, RuleScopes.READ)
            call.respond(RulesPage(listRules(caller.orgId, filters.toQuery()).map { it.toDto() }))
        }
        post<RulesApi> {
            val decider = call.decider(callers, actors)
            val rule = createRule(decider, call.receive<NewRuleRequest>().toNewRule())
            call.respond(HttpStatusCode.Created, rule.toDto())
        }
        get<RulesApi.Id> { path ->
            val caller = call.requireCaller(callers, RuleScopes.READ)
            call.respond(getRule(caller.orgId, path.id).toDto())
        }
        patch<RulesApi.Id> { path ->
            val decider = call.decider(callers, actors)
            call.respond(updateRule(decider, path.id, call.receive<JsonObject>().toRulePatch()).toDto())
        }
        delete<RulesApi.Id> { path ->
            deleteRule(call.decider(callers, actors), path.id)
            call.respond(HttpStatusCode.NoContent)
        }
        post<RulesApi.Bulk> {
            val decider = call.decider(callers, actors)
            val request = call.receive<BulkRulesRequest>()
            val status = ruleStatusOf(request.status)
            call.respond(BulkRulesResponse(bulkUpdateRules(decider, request.ids, status)))
        }
        get<DecisionsApi> { filters ->
            val caller = call.requireCaller(callers, RuleScopes.READ)
            val (items, next) = listDecisions(
                caller.orgId,
                DecisionQuery(filters.subjectKind, filters.subjectId, filters.before),
            )
            call.respond(DecisionsPage(items.map { it.toDto() }, next))
        }
        get<GatePolicyApi> {
            val caller = call.requireCaller(callers, RuleScopes.READ, GateScopes.READ)
            call.respond(getGatePolicy(caller.orgId).toDto())
        }
        put<GatePolicyApi> {
            val decider = call.decider(callers, actors, GovernanceScopes.GATES_POLICY)
            call.respond(setGatePolicy(decider, call.receive<GatePolicyDto>().toPolicy()).toDto())
        }
    }
}

/**
 * The person who decides: signed in to the console (a user's browser session, which holds every user scope) or holding
 * [scope] on the token of `ostrom login --admin`, owner or admin of the organization (checked at every request: a
 * demoted admin's token stops deciding at once), named by their GitHub login when the gates know it. Each decision is
 * signed and kept; the elevated token's use is also recorded in the audit log before the write ([auditGovernance]).
 *
 * @param callers Who the caller is in their organization.
 * @param actors Their GitHub login, when the gates know it.
 * @param scope The governance scope the write needs.
 */
internal suspend fun ApplicationCall.decider(
    callers: CallerResolver,
    actors: GateActorResolver,
    scope: String = GovernanceScopes.POLICY_WRITE,
): Decider {
    val principal = requirePrincipal()
    if (!principal.governs(scope)) throw OnlyConsoleDecidesException()
    val caller = requireCaller(callers, scope, writers = WriteAccess.governance(scope))
    caller.requireAdmin()
    auditGovernance(principal, scope)
    val name = actors.resolve(principal)?.personId ?: caller.memberId.toString()
    return Decider(caller.orgId, name)
}
