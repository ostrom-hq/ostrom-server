// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.rules.presentation

import run.ostrom.server.gates.domain.services.GateActorResolver
import run.ostrom.server.gates.presentation.GateScopes
import run.ostrom.server.kernel.auth.GovernanceScopes
import run.ostrom.server.kernel.auth.GovernanceWrite
import run.ostrom.server.kernel.auth.WriteAccess
import run.ostrom.server.kernel.auth.admitServiceKey
import run.ostrom.server.kernel.auth.auditGovernance
import run.ostrom.server.kernel.auth.authenticated
import run.ostrom.server.kernel.auth.confirmsByConsole
import run.ostrom.server.kernel.auth.governs
import run.ostrom.server.kernel.auth.holdForConfirmation
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.kernel.http.OstromJson
import run.ostrom.server.rules.domain.models.Decider
import run.ostrom.server.rules.domain.models.DecisionQuery
import run.ostrom.server.rules.domain.models.OnlyConsoleDecidesException
import run.ostrom.server.rules.domain.usecases.ListRulesUseCase
import run.ostrom.server.secrets.domain.CallerResolver
import run.ostrom.server.secrets.domain.OrgNotFoundException
import run.ostrom.server.secrets.presentation.requireCaller
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.uuid.Uuid

/**
 * The rules routes. A member, or a token with [RuleScopes.READ], reads the rules and the decisions (the policy too, or
 * with `gates:read`); only an owner or admin writes, decides or deletes a rule and sets the policy (CONVENTIONS 16.2).
 * Signed in to the console, the write is made at once; with a person's token (the CLI's, elevated by `ostrom login
 * --admin` or not), it is held until an owner or an admin confirms it on the console (202, audit CLI H1): an agent
 * running on the developer's machine can present that token. An agent or a device is always refused. A service key
 * with [RuleScopes.READ] reads the rules of its own organization for its CI (`listRules`, `getRule`), each call audited
 * in its name; it reads neither the decisions nor the policy, and never writes.
 *
 * @param dependencies The use cases.
 */
fun Route.rulesRoutes(dependencies: RulesRoutesDependencies) = with(dependencies) {
    authenticated {
        get<RulesApi> { filters ->
            val orgId = call.readerOrg(this@with, "listRules")
            val page = listRules(orgId, filters.toQuery(), filters.limit ?: ListRulesUseCase.MAX_RULES)
            call.respond(RulesPage(page.items.map { it.toDto() }, page.next?.wire))
        }
        post<RulesApi> {
            val writer = call.writer(this@with)
            val request = call.receive<NewRuleRequest>()
            call.write(writer, RulesWrites.CREATE, null, OstromJson.encodeToJsonElement(request)) { decider ->
                call.respond(HttpStatusCode.Created, createRule(decider, request.toNewRule()).toDto())
            }
        }
        get<RulesApi.Id> { path ->
            call.respond(getRule(call.readerOrg(this@with, "getRule"), path.id).toDto())
        }
        patch<RulesApi.Id> { path ->
            val writer = call.writer(this@with)
            val body = call.receive<JsonObject>()
            val patch = body.toRulePatch()
            call.write(writer, RulesWrites.UPDATE, path.id, body) { decider ->
                call.respond(updateRule(decider, path.id, patch).toDto())
            }
        }
        delete<RulesApi.Id> { path ->
            call.write(call.writer(this@with), RulesWrites.DELETE, path.id, null) { decider ->
                deleteRule(decider, path.id)
                call.respond(HttpStatusCode.NoContent)
            }
        }
        post<RulesApi.Bulk> {
            val writer = call.writer(this@with)
            val request = call.receive<BulkRulesRequest>()
            val status = ruleStatusOf(request.status)
            call.write(writer, RulesWrites.BULK, null, OstromJson.encodeToJsonElement(request)) { decider ->
                call.respond(BulkRulesResponse(bulkUpdateRules(decider, request.ids, status)))
            }
        }
        get<DecisionsApi> { filters ->
            val caller = call.requireCaller(callers, RuleScopes.READ)
            val query = DecisionQuery(filters.subjectKind, filters.subjectId, filters.before)
            val (items, next) = listDecisions(caller.orgId, query)
            call.respond(DecisionsPage(items.map { it.toDto() }, next))
        }
        get<GatePolicyApi> {
            val caller = call.requireCaller(callers, RuleScopes.READ, GateScopes.READ)
            call.respond(getGatePolicy(caller.orgId).toDto())
        }
        put<GatePolicyApi> {
            val writer = call.writer(this@with, GovernanceScopes.GATES_POLICY)
            val request = call.receive<GatePolicyDto>()
            val policy = request.toPolicy()
            call.write(writer, RulesWrites.GATE_POLICY, null, OstromJson.encodeToJsonElement(request)) { decider ->
                call.respond(setGatePolicy(decider, policy).toDto())
            }
        }
    }
}

/** Who makes a governance write: the decider at once, or a token's write held for the console. */
private sealed interface Writer {
    /** Signed in to the console: the write is made now, as [decider]'s decision. */
    class Now(val decider: Decider) : Writer

    /** A person's token, an owner or admin of [orgId]: the write waits for a confirmation. */
    class Held(val orgId: Uuid, val scope: String) : Writer
}

/**
 * The organization whose rules a caller reads: a service key's own with [RuleScopes.READ], audited; a member's, or a
 * token's with that scope, as on the other reads.
 */
private suspend fun ApplicationCall.readerOrg(dependencies: RulesRoutesDependencies, operation: String): Uuid =
    requirePrincipal().admitServiceKey(dependencies.serviceKeys, RuleScopes.READ, operation, request.path())
        ?: requireCaller(dependencies.callers, RuleScopes.READ).orgId

/**
 * The person who decides an action that is not a held governance write (starting an analysis or a replay): signed in
 * to the console (a user's browser session, which holds every user scope) or holding [scope] on the token of
 * `ostrom login --admin`, owner or admin of the organization (checked at every request: a demoted admin's token stops
 * deciding at once), named by their GitHub login when the gates know it. The elevated token's use is recorded in the
 * audit log first ([auditGovernance]).
 *
 * @param callers Who the caller is in their organization.
 * @param actors Their GitHub login, when the gates know it.
 * @param scope The governance scope the action needs.
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
    return Decider(caller.orgId, name, principal)
}

/**
 * Who writes, checked before the body is read: an owner or admin (checked at every request: a demoted admin stops at
 * once), signed in to the console, or with a person's token, whose write is then held ([Writer.Held]). The decider is
 * named by their GitHub login when the gates know it. Each decision is signed and kept.
 *
 * @param dependencies Who the caller is in their organization, and their GitHub login.
 * @param scope The governance scope the write needs.
 */
private suspend fun ApplicationCall.writer(
    dependencies: RulesRoutesDependencies,
    scope: String = GovernanceScopes.POLICY_WRITE,
): Writer {
    val principal = requirePrincipal()
    if (principal.confirmsByConsole) {
        val caller = dependencies.callers.resolve(principal, null) ?: throw OrgNotFoundException()
        caller.requireAdmin()
        return Writer.Held(caller.orgId, scope)
    }
    if (!principal.governs(scope)) throw OnlyConsoleDecidesException()
    val caller = requireCaller(dependencies.callers, scope)
    caller.requireAdmin()
    val name = dependencies.actors.resolve(principal)?.personId ?: caller.memberId.toString()
    return Writer.Now(Decider(caller.orgId, name, principal))
}

/** Makes the write now, or holds it for the console with the path's [id] and the [body] as asked. */
private suspend fun ApplicationCall.write(
    writer: Writer,
    kind: String,
    id: String?,
    body: JsonElement?,
    now: suspend (Decider) -> Unit,
) = when (writer) {
    is Writer.Now -> now(writer.decider)
    is Writer.Held ->
        holdForConfirmation(writer.orgId, writer.scope, GovernanceWrite(kind, RulesGovernanceChange.payload(id, body)))
}
