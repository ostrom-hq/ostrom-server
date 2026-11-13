// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.workflowruns.infrastructure

import run.ostrom.server.automations.domain.WaitsRepository
import run.ostrom.server.launches.domain.repositories.LaunchesRepository
import run.ostrom.server.launches.domain.services.LauncherResolver
import run.ostrom.server.runners.domain.CloudSessionsRepository
import run.ostrom.server.sessions.domain.models.SessionQuery
import run.ostrom.server.sessions.domain.repositories.SessionsRepository
import run.ostrom.server.workflowruns.domain.services.StepSessions
import kotlin.uuid.Uuid

/**
 * [StepSessions] on the runners module's cloud sessions (their owner), the launches that ran in one, the sessions
 * module's segments those launches opened, and the automations module's waits asked there.
 *
 * @param cloud The cloud sessions.
 * @param members Resolves a login to its membership.
 * @param launches The launches.
 * @param sessions The sessions module's segments.
 * @param waits The waits.
 */
class CloudStepSessions(
    private val cloud: CloudSessionsRepository,
    private val members: LauncherResolver,
    private val launches: LaunchesRepository,
    private val sessions: SessionsRepository,
    private val waits: WaitsRepository,
) : StepSessions {
    override suspend fun ownedBy(orgId: Uuid, sessionId: Uuid, login: String): Boolean {
        val owner = cloud.get(orgId, sessionId)?.ownerId ?: return false
        val member = members.colleague(orgId, login)?.let { members.member(orgId, it.userId) }
        return member?.memberId?.toString() == owner
    }

    override suspend fun asked(orgId: Uuid, sessionId: Uuid): Boolean {
        // A cloud session is launched once, then again on each resume: the latest few hold any wait still open.
        val segments = launches.ofCloudSession(orgId, sessionId, LAUNCHES).flatMap { launch ->
            sessions.list(orgId, SessionQuery(launchId = launch)).map { it.id }
        }
        return segments.isNotEmpty() && waits.hasOpen(orgId, segments)
    }
}

// The most launches of one cloud session whose waits are read.
private const val LAUNCHES = 5
