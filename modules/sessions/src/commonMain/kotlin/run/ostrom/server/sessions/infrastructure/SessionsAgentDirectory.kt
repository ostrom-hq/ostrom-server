// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sessions.infrastructure

import run.ostrom.server.kernel.sessions.AgentSession
import run.ostrom.server.kernel.sessions.AgentSessions
import run.ostrom.server.sessions.domain.repositories.SessionsRepository
import kotlin.uuid.Uuid

/**
 * [AgentSessions] on the segments: one read of the session's segments, whichever of them is named. Its owner is the
 * person of its last segment (every segment of a session is the same person's: a resume by someone else is refused).
 *
 * @param repository The sessions.
 */
class SessionsAgentDirectory(private val repository: SessionsRepository) : AgentSessions {
    override suspend fun find(orgId: Uuid, sessionId: String): AgentSession? {
        val segments = repository.segmentsOf(orgId, sessionId)
        val last = segments.lastOrNull() ?: return null
        return AgentSession(last.dev, last.endedAt == null, segments.map { it.id })
    }
}
