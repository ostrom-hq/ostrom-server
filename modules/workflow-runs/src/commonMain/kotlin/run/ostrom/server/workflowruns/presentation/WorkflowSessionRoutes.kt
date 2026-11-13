// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.workflowruns.presentation

import run.ostrom.core.api.models.DeliverSessionRequest
import run.ostrom.server.identity.domain.WORKFLOW_SESSION_SCOPE
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.outsideOrganization
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.workflowruns.domain.models.CredentialScopeException
import run.ostrom.server.workflowruns.domain.models.InvalidRunException
import run.ostrom.server.workflowruns.domain.models.RunSessionNotFoundException
import run.ostrom.server.workflowruns.domain.usecases.StepCaller
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlin.uuid.Uuid

/**
 * The agent step of a session (`workflows.deliver.v1`): its delivery (`deliverWorkflowSession`) and whether it still
 * waits for one (`pendingWorkflowSession`, the Stop hook). The session's agent token, with `workflow:session`, acts on
 * its launch's own cloud session whatever the request names; the run's starter names it, with a person's credential
 * that writes workflows. A service key, or an agent token issued before deliveries, is `credential_scope`. Mounted
 * inside the authenticated routes of [workflowRunsRoutes].
 *
 * @param dependencies The use cases.
 */
internal fun Route.workflowSessionRoutes(dependencies: WorkflowRunsRoutesDependencies) = with(dependencies) {
    post<WorkflowSessionDeliverApi> {
        val body = call.receive<DeliverSessionRequest>()
        val (caller, session) = stepCallerOf(call, body.sessionId)
        call.respond(deliverSession(caller, session, body.output, Uuid.parseOrNull(call.requirePrincipal().subjectId)))
    }
    get<WorkflowSessionPendingApi> { params ->
        val (caller, session) = stepCallerOf(call, params.sessionId)
        call.respond(pendingSession(caller, session))
    }
}

// Who asks, and for which cloud session: an agent token's launch's own, or the one a person names.
private suspend fun WorkflowRunsRoutesDependencies.stepCallerOf(
    call: ApplicationCall,
    named: String?,
): Pair<StepCaller, Uuid?> {
    val principal = call.requirePrincipal()
    return when (principal.kind) {
        PrincipalKind.AGENT -> agentCallerOf(principal)
        PrincipalKind.SERVICE -> throw CredentialScopeException()
        else -> personCallerOf(call, named)
    }
}

// The session's agent token: only with `workflow:session`, which a token issued before deliveries lacks.
private suspend fun WorkflowRunsRoutesDependencies.agentCallerOf(
    principal: AuthenticatedPrincipal,
): Pair<StepCaller, Uuid?> {
    if (WORKFLOW_SESSION_SCOPE !in principal.scopes) throw CredentialScopeException()
    val actor = actors.resolve(principal) ?: throw principal.outsideOrganization()
    // An agent actor always names its launch and its person; without them it is nobody's (fail closed).
    return StepCaller(actor.orgId, actor.personId.orEmpty(), launchId = actor.launchId.orEmpty()) to null
}

// A person who writes workflows, naming the session.
private suspend fun WorkflowRunsRoutesDependencies.personCallerOf(
    call: ApplicationCall,
    named: String?,
): Pair<StepCaller, Uuid?> {
    val actor = actorOf(call, RunScopes.starting)
    val text = named ?: throw InvalidRunException("sessionId: the session the step runs in.")
    val session = Uuid.parseOrNull(text) ?: throw RunSessionNotFoundException()
    return StepCaller(actor.orgId, actor.id) to session
}
