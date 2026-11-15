// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.automations.domain.WaitSecretSessions
import run.ostrom.server.runners.domain.CloudSessionsRepository
import kotlin.uuid.Uuid

/**
 * The automations' [WaitSecretSessions] on cloud sessions: a secret field is asked in a cloud session that runs, and
 * reaches its agent through the runner of the session (`fetchRunnerVariables`).
 *
 * @param repository Reads the sessions.
 */
internal class CloudSessionWaitSecretSessions(private val repository: CloudSessionsRepository) :
    WaitSecretSessions {
    override suspend fun agentOf(orgId: Uuid, sessionId: String): String? {
        val session = Uuid.parseOrNull(sessionId)?.let { repository.get(orgId, it) }
        return session?.takeIf { it.state.holdsExecution }?.let { it.agent.wire }
    }
}
