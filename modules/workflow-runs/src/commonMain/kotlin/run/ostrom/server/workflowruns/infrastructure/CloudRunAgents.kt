// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.workflowruns.infrastructure

import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.launches.domain.services.LauncherResolver
import run.ostrom.server.runners.domain.Caller
import run.ostrom.server.runners.domain.CloudSessionsRepository
import run.ostrom.server.runners.domain.CreateCloudSessionPayload
import run.ostrom.server.runners.domain.CreateCloudSessionUseCase
import run.ostrom.server.runners.domain.DesiredState
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.workflowruns.domain.services.AgentRequest
import run.ostrom.server.workflowruns.domain.services.AgentState
import run.ostrom.server.workflowruns.domain.services.RunAgents
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * [RunAgents] on the runners module: an agent node's session is a cloud session created by the same use case as
 * `POST /api/v1/cloud/sessions` (the organization's plan, its running cap and its hours), owned by the run's starter;
 * its runner then opens its launch through the launch path (policy, rate limit, agent token), as for any cloud session.
 *
 * @param create Creates a cloud session.
 * @param sessions The cloud sessions.
 * @param launchers Resolves the starter's login to their membership.
 * @param clock The clock.
 */
class CloudRunAgents(
    private val create: CreateCloudSessionUseCase,
    private val sessions: CloudSessionsRepository,
    private val launchers: LauncherResolver,
    private val clock: Clock,
) : RunAgents {
    override suspend fun start(orgId: Uuid, request: AgentRequest): String? {
        val member = launchers.colleague(orgId, request.starter)?.let { launchers.member(orgId, it.userId) }
            ?: return "The person who started the run is no longer a member of the organization."
        val payload = CreateCloudSessionPayload(
            name = request.name,
            agent = request.agent,
            repository = null,
            model = request.model,
            prompt = request.prompt,
            id = request.sessionId,
        )
        // A refusal of the plan or of the request (its stable message) fails the visit; anything else is retried.
        return try {
            create(Caller(orgId, member.memberId.toString()), payload)
            null
        } catch (refused: OstromException) {
            refused.message
        }
    }

    override suspend fun state(orgId: Uuid, sessionId: Uuid): AgentState? = sessions.get(orgId, sessionId)?.let {
        when {
            it.state == SessionState.FAILED -> AgentState.FAILED
            it.state.holdsExecution -> AgentState.WORKING
            else -> AgentState.DONE
        }
    }

    override suspend fun stop(orgId: Uuid, sessionId: Uuid) {
        sessions.setDesiredState(orgId, sessionId, DesiredState.TERMINATED, clock.now())
    }
}
