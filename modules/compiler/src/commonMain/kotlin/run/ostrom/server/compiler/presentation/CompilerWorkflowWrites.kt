// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.compiler.presentation

import run.ostrom.server.gates.domain.models.GateActor
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.kernel.http.OstromJson
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement

internal fun Route.settingsRoutes(dependencies: CompilerRoutesDependencies) = with(dependencies) {
    get<WorkflowsApi.Settings> {
        val actor = actorOf(call, CompilerScopes.readingWorkflows)
        call.respond(settings.settings(actor.orgId, actor.id, actor.role))
    }
    put<WorkflowsApi.Settings> {
        val actor = personOf(call, CompilerScopes.writingWorkflows)
        // The rule changes who decides: a browser session only, as the decisions it governs (SECURITY-AUDIT S2-04).
        if (!call.decides(may = true)) throw OstromException(ErrorKind.FORBIDDEN, CONSOLE_ONLY)
        val saved = settings.save(actor.orgId, actor.id, actor.role, call.requirePrincipal(), call.receive())
        call.respond(saved)
    }
}

private const val CONSOLE_ONLY = "Only a person signed in to the console changes who may edit workflows."

/**
 * Saves a workflow as the person may: at once when they decide it, held for the console when their token asks a
 * decision, and only as a proposal otherwise.
 *
 * @return The saved document, or null once the write is held (the answer is sent).
 */
internal suspend fun CompilerRoutesDependencies.saveWorkflow(
    call: ApplicationCall,
    actor: GateActor,
    id: String,
    body: SaveWorkflowRequest,
): JsonElement? {
    if ((body.workflow["id"] as? JsonPrimitive)?.content != id) {
        throw OstromException(ErrorKind.INVALID_REQUEST, "The workflow's id is not the path's.")
    }
    val stored = repository.workflow(actor.orgId, id)?.let { it.doc.raw }
    val gate = gated(body.workflow) || (stored != null && gated(stored))
    if (!call.decides(mayDecide(actor, gate))) {
        val proposal = proposed(body.workflow) && proposed(stored ?: workflows.get(actor.orgId, id).doc.raw)
        if (holds(call, actor, gate, proposal)) {
            val payload = CompilerGovernanceChange.payload(id, OstromJson.encodeToJsonElement(body))
            call.hold(actor, WorkflowWrites.SAVE, payload)
            return null
        }
        if (!proposal) throw refusal(actor, gate)
    }
    return documentOf(workflows.save(actor.orgId, body.workflow, body.expectedVersion, actor.id, create = false))
}
