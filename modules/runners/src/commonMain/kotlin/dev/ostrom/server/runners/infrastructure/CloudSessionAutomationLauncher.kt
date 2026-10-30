// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners.infrastructure

import dev.ostrom.server.automations.domain.AutomationLauncher
import dev.ostrom.server.automations.domain.LaunchRequest
import dev.ostrom.server.automations.domain.LaunchResult
import dev.ostrom.server.runners.domain.Caller
import dev.ostrom.server.runners.domain.CloudHoursExhaustedException
import dev.ostrom.server.runners.domain.CloudSessionLimitException
import dev.ostrom.server.runners.domain.CloudSessionsClosedException
import dev.ostrom.server.runners.domain.CloudSessionsRepository
import dev.ostrom.server.runners.domain.CreateCloudSessionPayload
import dev.ostrom.server.runners.domain.CreateCloudSessionUseCase
import dev.ostrom.server.runners.domain.InvalidCloudSessionRequestException
import kotlin.uuid.Uuid

/**
 * The automations' [AutomationLauncher] on cloud sessions: a run starts a session owned by the automation's owner,
 * through [CreateCloudSessionUseCase], so the organization's plan (allowlist, running cap, monthly hours) applies to
 * automations exactly as to a member. An expected refusal becomes a [LaunchResult.Refused] with a stable reason the
 * run history shows; anything else propagates, and the automation records `launch_failed`.
 *
 * @param create Creates sessions.
 * @param repository Reads a run's session back, to tell whether it still works.
 */
internal class CloudSessionAutomationLauncher(
    private val create: CreateCloudSessionUseCase,
    private val repository: CloudSessionsRepository,
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
}
