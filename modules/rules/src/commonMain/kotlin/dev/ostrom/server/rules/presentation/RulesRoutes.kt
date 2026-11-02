// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules.presentation

import dev.ostrom.server.gates.presentation.GateScopes
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.admitServiceKey
import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.kernel.auth.requirePrincipal
import dev.ostrom.server.rules.domain.models.Decider
import dev.ostrom.server.rules.domain.models.DecisionQuery
import dev.ostrom.server.rules.domain.models.OnlyConsoleDecidesException
import dev.ostrom.server.secrets.domain.RelayScopes
import dev.ostrom.server.secrets.presentation.requireCaller
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.patch
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.Uuid

/**
 * The rules routes. A member, or a token with [RuleScopes.READ], reads the rules and the decisions (the policy too, or
 * with `gates:read`); only an owner or admin signed in to the console writes, decides or deletes a rule and sets the
 * policy (CONVENTIONS 16.2): a token is refused even with every scope, and a device always is. A service key with
 * [RuleScopes.READ] reads the rules of its own organization for its CI (`listRules`, `getRule`), each call audited in
 * its name; it reads neither the decisions nor the policy, and never writes.
 *
 * @param dependencies The use cases.
 */
fun Route.rulesRoutes(dependencies: RulesRoutesDependencies) = with(dependencies) {
    authenticated {
        get<RulesApi> { filters ->
            val orgId = call.readerOrg(this@with, "listRules")
            call.respond(RulesPage(listRules(orgId, filters.toQuery()).map { it.toDto() }))
        }
        post<RulesApi> {
            val decider = call.decider(this@with)
            val rule = createRule(decider, call.receive<NewRuleRequest>().toNewRule())
            call.respond(HttpStatusCode.Created, rule.toDto())
        }
        get<RulesApi.Id> { path ->
            call.respond(getRule(call.readerOrg(this@with, "getRule"), path.id).toDto())
        }
        patch<RulesApi.Id> { path ->
            val decider = call.decider(this@with)
            call.respond(updateRule(decider, path.id, call.receive<JsonObject>().toRulePatch()).toDto())
        }
        delete<RulesApi.Id> { path ->
            deleteRule(call.decider(this@with), path.id)
            call.respond(HttpStatusCode.NoContent)
        }
        post<RulesApi.Bulk> {
            val decider = call.decider(this@with)
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
            val decider = call.decider(this@with)
            call.respond(setGatePolicy(decider, call.receive<GatePolicyDto>().toPolicy()).toDto())
        }
    }
}

/**
 * The organization whose rules a caller reads: a service key's own with [RuleScopes.READ], audited; a member's, or a
 * token's with that scope, as on the other reads.
 */
private suspend fun ApplicationCall.readerOrg(dependencies: RulesRoutesDependencies, operation: String): Uuid =
    requirePrincipal().admitServiceKey(dependencies.serviceKeys, RuleScopes.READ, operation, request.path())
        ?: requireCaller(dependencies.callers, RuleScopes.READ).orgId

/**
 * The person who decides: signed in to the console (a user's browser session, which holds every user scope), owner or
 * admin of the organization, named by their GitHub login when the gates know it.
 */
private suspend fun ApplicationCall.decider(dependencies: RulesRoutesDependencies): Decider {
    val principal = requirePrincipal()
    if (principal.kind != PrincipalKind.USER || RelayScopes.ALL_USER !in principal.scopes) {
        throw OnlyConsoleDecidesException()
    }
    val caller = requireCaller(dependencies.callers)
    caller.requireAdmin()
    val name = dependencies.actors.resolve(principal)?.personId ?: caller.memberId.toString()
    return Decider(caller.orgId, name)
}
