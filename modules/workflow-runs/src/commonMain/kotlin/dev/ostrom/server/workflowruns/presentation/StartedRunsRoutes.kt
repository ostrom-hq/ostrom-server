// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.workflowruns.presentation

import dev.ostrom.core.api.models.RunStatus
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.workflowruns.domain.models.InvalidRunException
import dev.ostrom.server.workflowruns.domain.repositories.RunsQuery
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/**
 * The runs of workflows: started by an owner or an admin with a person's credential, read by any member; never by an
 * agent token. Mounted inside the authenticated routes of [workflowRunsRoutes].
 *
 * @param dependencies The use cases.
 */
internal fun Route.startedRunsRoutes(dependencies: WorkflowRunsRoutesDependencies) = with(dependencies) {
    post<WorkflowRunsOfApi> { params ->
        val actor = actorOf(call, RunScopes.starting)
        val body = call.receive<StartRunRequest>()
        call.respond(HttpStatusCode.Created, startRun(actor, params.id, body.inputs))
    }
    get<WorkflowRunsOfApi> { params ->
        val actor = actorOf(call, RunScopes.reading)
        respondRuns(call, actor, RunsQuery(params.id, runStatusOf(params.status), params.before))
    }
    get<RunsApi> { params ->
        val actor = actorOf(call, RunScopes.reading)
        respondRuns(call, actor, RunsQuery(null, runStatusOf(params.status), params.before))
    }
}

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
