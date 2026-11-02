// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules.presentation

import dev.ostrom.server.compiler.domain.models.StoredWorkflow
import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.receipts.domain.usecases.isoMillis
import dev.ostrom.server.rules.domain.models.AnalysisKind
import dev.ostrom.server.rules.domain.models.AnalysisRun
import dev.ostrom.server.rules.domain.usecases.AnalysisSnapshot
import dev.ostrom.server.secrets.presentation.requireCaller
import io.ktor.http.HttpStatusCode
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The analysis routes: an owner or admin in the console starts a run (it reads every repository and spends on the
 * organization's model), a reader reads the result.
 *
 * @param dependencies What they need.
 */
fun Route.analysisRoutes(dependencies: AnalysisRoutesDependencies) = with(dependencies) {
    authenticated {
        post<AnalysisApi.Start> {
            val run = start(call.decider(callers, actors), AnalysisKind.ANALYSIS)
            call.respond(HttpStatusCode.Accepted, AnalysisStarted(run.id))
        }
        post<AnalysisApi.Restart> {
            val run = start(call.decider(callers, actors), AnalysisKind.ANALYSIS)
            call.respond(HttpStatusCode.Accepted, AnalysisStarted(run.id))
        }
        get<AnalysisApi.State> {
            val caller = call.requireCaller(callers, RuleScopes.READ)
            call.respond(state(caller.orgId).toDto())
        }
        post<AnalysisApi.Replay> {
            val run = start(call.decider(callers, actors), AnalysisKind.REPLAY)
            call.respond(HttpStatusCode.Accepted, AnalysisStarted(run.id))
        }
        get<AnalysisApi.Replay> {
            val caller = call.requireCaller(callers, RuleScopes.READ)
            call.respond(ReplayPage(replay(caller.orgId)))
        }
    }
}

/**
 * The snapshot as the API serves it. The repositories stay empty until a module serves them (the analysis reads them
 * through the engine).
 *
 * @return The DTO.
 */
fun AnalysisSnapshot.toDto(): AnalysisStateDto = AnalysisStateDto(
    phase = run?.phase,
    progress = run?.progress,
    repos = emptyList(),
    rules = rules.map { it.toDto() },
    workflows = workflows.map { it.toDocument() },
    failure = run?.failureFields(),
)

private fun AnalysisRun.failureFields(): Map<String, String>? = failure?.let {
    mapOf("stage" to it.stage, "reason" to it.reason, "at" to it.at.isoMillis(), "retryPath" to it.retryPath)
}

// As the compiler's `GET /workflows` serves it: the saved JSON, its version and who saved it.
private fun StoredWorkflow.toDocument(): JsonObject = JsonObject(
    doc.raw + mapOf(
        "version" to JsonPrimitive(version),
        "updatedBy" to JsonPrimitive(updatedBy),
        "updatedAt" to JsonPrimitive(updatedAt.toString()),
    ),
)
