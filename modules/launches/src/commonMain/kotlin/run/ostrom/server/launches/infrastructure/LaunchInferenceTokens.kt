// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.launches.infrastructure

import run.ostrom.server.identity.domain.RenewAgentTokenUseCase
import run.ostrom.server.identity.domain.RevokeAgentTokenUseCase
import run.ostrom.server.launches.domain.models.Launch
import run.ostrom.server.launches.domain.repositories.LaunchesRepository
import run.ostrom.server.runners.domain.CloudSession
import run.ostrom.server.runners.domain.InferenceToken
import run.ostrom.server.runners.domain.RunnerInferenceTokens
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The runners module's [RunnerInferenceTokens] on the launches and the identity module's agent tokens: the execution's
 * inference credential is another agent token of the launch its runner opened (`POST /api/v1/cloud/runner/launch`),
 * so the inference gateway reads it as that launch's agent ([LaunchAgents]: the owner's membership as it is now, and
 * only while the execution the launch was opened in runs). The launch is the session's newest one, opened since the
 * current execution started, not expired, and with a live agent token (one ended by its agent has none).
 *
 * @param launches The launches.
 * @param renew Issues another agent token of a live launch.
 * @param revoker Revokes one agent token.
 * @param clock The clock.
 */
class LaunchInferenceTokens(
    private val launches: LaunchesRepository,
    private val renew: RenewAgentTokenUseCase,
    private val revoker: RevokeAgentTokenUseCase,
    private val clock: Clock,
) : RunnerInferenceTokens {
    override suspend fun issue(session: CloudSession, lifetime: Duration): InferenceToken? {
        val now = clock.now()
        val launch = openLaunch(session, now) ?: return null
        // The launch expires after `now` (openLaunch), so the lifetime is always positive.
        val life = minOf(lifetime, launch.expiresAt - now)
        return renew(launch.userId, launch.orgId, launch.id, life)?.let { issued ->
            InferenceToken(issued.token.id, issued.secret, issued.token.createdAt + life)
        }
    }

    // The session's newest launch, if opened since its current execution started and not expired: a launch of an
    // earlier execution acts no more (RunnerCloudExecutions), this execution opens its own.
    private suspend fun openLaunch(session: CloudSession, now: Instant): Launch? {
        val started = session.executionStartedAt ?: return null
        val id = launches.ofCloudSession(session.orgId, session.id, 1).firstOrNull()
        return id?.let { launches.find(session.orgId, it) }?.takeIf { it.createdAt >= started && it.expiresAt > now }
    }

    override suspend fun revoke(orgId: Uuid, ids: List<Uuid>) {
        ids.forEach { revoker(orgId, it) }
    }
}
