// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.automations.domain.AutomationLauncher
import run.ostrom.server.automations.domain.LaunchRequest
import run.ostrom.server.automations.domain.LaunchResult
import run.ostrom.server.runners.domain.Caller
import run.ostrom.server.runners.domain.ChangeCloudSessionUseCase
import run.ostrom.server.runners.domain.CloudHoursExhaustedException
import run.ostrom.server.runners.domain.CloudSessionLimitException
import run.ostrom.server.runners.domain.CloudSessionNotFoundException
import run.ostrom.server.runners.domain.CloudSessionsClosedException
import run.ostrom.server.runners.domain.CloudSessionStateException
import run.ostrom.server.runners.domain.CloudSessionsRepository
import run.ostrom.server.runners.domain.CreateCloudSessionPayload
import run.ostrom.server.runners.domain.CreateCloudSessionUseCase
import run.ostrom.server.runners.domain.InvalidCloudSessionRequestException
import kotlin.uuid.Uuid

/**
 * The automations' [AutomationLauncher] on cloud sessions: a run starts a session owned by the automation's owner,
 * through [CreateCloudSessionUseCase], so the organization's plan (allowlist, running cap, hours) applies to
 * automations exactly as to a member. An expected refusal becomes a [LaunchResult.Refused] with a stable reason the
 * run history shows; anything else propagates, and the automation records `launch_failed`.
 *
 * @param create Creates sessions.
 * @param repository Reads a run's session back, to tell whether it still works.
 * @param change Pauses the session of a cancelled run.
 */
internal class CloudSessionAutomationLauncher(
    private val create: CreateCloudSessionUseCase,
    private val repository: CloudSessionsRepository,
    private val change: ChangeCloudSessionUseCase,
) : AutomationLauncher {
    override suspend fun launch(request: LaunchRequest): LaunchResult {
        val payload = CreateCloudSessionPayload(
            name = request.name,
            agent = request.action.agent,
            repository = null,
            model = request.action.model,
            prompt = request.prompt,
        )
        val refusal = try {
            return LaunchResult.Started(create(Caller(request.orgId, request.owner), payload).id.toString())
        } catch (_: CloudSessionsClosedException) {
            "cloud_sessions_unavailable"
        } catch (_: CloudSessionLimitException) {
            "cloud_session_limit"
        } catch (_: CloudHoursExhaustedException) {
            "cloud_hours_exhausted"
        } catch (_: InvalidCloudSessionRequestException) {
            "invalid_session_request"
        }
        return LaunchResult.Refused(refusal)
    }

    override suspend fun isWorking(orgId: Uuid, sessionId: String): Boolean {
        val id = Uuid.parseOrNull(sessionId) ?: return false
        val session = repository.get(orgId, id)
        return session != null && session.state.holdsExecution
    }

    override suspend fun pause(orgId: Uuid, owner: String, sessionId: String): Boolean {
        val id = Uuid.parseOrNull(sessionId) ?: return false
        return try {
            change.pause(Caller(orgId, owner), id)
            true
        } catch (_: CloudSessionNotFoundException) {
            false
        } catch (_: CloudSessionStateException) {
            false
        }
    }
}
