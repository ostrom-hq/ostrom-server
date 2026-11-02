// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules.presentation

import dev.ostrom.server.gates.presentation.GateScopes
import dev.ostrom.server.kernel.auth.GovernanceScopes
import dev.ostrom.server.kernel.auth.GovernanceWrite
import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.kernel.auth.confirmsByConsole
import dev.ostrom.server.kernel.auth.governs
import dev.ostrom.server.kernel.auth.holdForConfirmation
import dev.ostrom.server.kernel.auth.requirePrincipal
import dev.ostrom.server.kernel.http.OstromJson
import dev.ostrom.server.rules.domain.models.Decider
import dev.ostrom.server.rules.domain.models.DecisionQuery
import dev.ostrom.server.rules.domain.models.OnlyConsoleDecidesException
import dev.ostrom.server.secrets.domain.OrgNotFoundException
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.uuid.Uuid

/**
 * The rules routes. A member, or a token with [RuleScopes.READ], reads the rules and the decisions (the policy too, or
 * with `gates:read`); only an owner or admin writes, decides or deletes a rule and sets the policy (CONVENTIONS 16.2).
 * Signed in to the console, the write is made at once; with a person's token (the CLI's, elevated by `ostrom login
 * --admin` or not), it is held until an owner or an admin confirms it on the console (202, audit CLI H1): an agent
 * running on the developer's machine can present that token. An agent, a device or a service is always refused.
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
            val writer = call.writer(this@with)
            val request = call.receive<NewRuleRequest>()
            call.write(writer, RulesWrites.CREATE, null, OstromJson.encodeToJsonElement(request)) { decider ->
                call.respond(HttpStatusCode.Created, createRule(decider, request.toNewRule()).toDto())
            }
        }
        get<RulesApi.Id> { path ->
            val caller = call.requireCaller(callers, RuleScopes.READ)
            call.respond(getRule(caller.orgId, path.id).toDto())
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
 * Who writes, checked before the body is read: an owner or admin (checked at every request: a demoted admin stops at
 * once), signed in to the console, or with a person's token, whose write is then held ([Writer.Held]). The decider is
 * named by their GitHub login when the gates know it. Each decision is signed and kept.
 *
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
    return Writer.Now(Decider(caller.orgId, name))
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
