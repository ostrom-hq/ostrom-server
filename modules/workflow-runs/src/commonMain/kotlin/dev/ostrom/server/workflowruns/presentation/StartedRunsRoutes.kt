// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.workflowruns.presentation

import dev.ostrom.core.api.models.RunStatus
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.kernel.auth.requirePrincipal
import dev.ostrom.server.workflowruns.domain.models.InvalidRunException
import dev.ostrom.server.workflowruns.domain.repositories.RunsQuery
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlin.uuid.Uuid

/**
 * The runs of workflows: started and cancelled by an owner or an admin with a person's credential, read by any member;
 * never by an agent token. Mounted inside the authenticated routes of [workflowRunsRoutes].
 *
 * @param dependencies The use cases.
 */
internal fun Route.startedRunsRoutes(dependencies: WorkflowRunsRoutesDependencies) = with(dependencies) {
    post<WorkflowRunsOfApi> { params ->
        val actor = actorOf(call, RunScopes.starting)
        val body = call.receive<StartRunRequest>()
        call.respond(HttpStatusCode.Created, startRun(actor, params.id, body.inputs, personOf(call)))
    }
    get<WorkflowRunsOfApi> { params ->
        val actor = actorOf(call, RunScopes.reading)
        respondRuns(call, actor, RunsQuery(params.id, runStatusOf(params.status), params.before))
    }
    get<RunsApi> { params ->
        val actor = actorOf(call, RunScopes.reading)
        respondRuns(call, actor, RunsQuery(null, runStatusOf(params.status), params.before))
    }
    get<RunApi> { params ->
        call.respond(getRun(actorOf(call, RunScopes.reading), params.id))
    }
    post<RunCancelApi> { params ->
        val actor = actorOf(call, RunScopes.starting)
        call.respond(cancelRun(actor, params.id, personOf(call)))
    }
}

// The person's id, for the audit log: a person's credential names its user.
private fun personOf(call: ApplicationCall): Uuid? = Uuid.parseOrNull(call.requirePrincipal().subjectId)

private suspend fun WorkflowRunsRoutesDependencies.respondRuns(
    call: ApplicationCall,
    actor: GateActor,
    query: RunsQuery,
) {
    val items = listRuns(actor, query)
    call.respond(RunsPage(items, nextOf(items.map { it.id }, query.limit)))
}

private fun runStatusOf(value: String?): RunStatus? = value?.let { text ->
    RunStatus.entries.filter { it != RunStatus.UNKNOWN }.firstOrNull { it.value == text }
        ?: throw InvalidRunException("status: queued, running, waiting, succeeded, failed or cancelled.")
}
