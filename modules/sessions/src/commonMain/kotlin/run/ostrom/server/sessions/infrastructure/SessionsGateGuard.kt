// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sessions.infrastructure

import run.ostrom.server.gates.domain.services.GateSessions
import run.ostrom.server.gates.domain.services.OpenSegment
import run.ostrom.server.sessions.domain.models.NotYourSessionException
import run.ostrom.server.sessions.domain.models.Session
import run.ostrom.server.sessions.domain.models.SessionClosedException
import run.ostrom.server.sessions.domain.models.SessionNotFoundException
import run.ostrom.server.sessions.domain.repositories.SessionsRepository
import kotlin.uuid.Uuid

/**
 * The sessions' side of a gate: a gate is asked only in an open session (CORRECTIFS-4: a closed session's receipt is
 * signed, nothing more is added to it), and an agent token asks only in its own launch's sessions (audit A12). The
 * waits MCP server also finds an agent session's open segment here, from whichever of its ids the agent names.
 *
 * @param repository The sessions.
 */
class SessionsGateGuard(private val repository: SessionsRepository) : GateSessions {
    override suspend fun assertOpen(orgId: Uuid, sessionId: String, launchId: String?) {
        ensureOpen(repository.get(orgId, sessionId) ?: throw SessionNotFoundException(), launchId)
    }

    override suspend fun openSegment(orgId: Uuid, sessionId: String, launchId: String?): OpenSegment {
        val segments = repository.segmentsOf(orgId, sessionId)
        val last = segments.lastOrNull() ?: throw SessionNotFoundException()
        ensureOpen(last, launchId)
        return OpenSegment(last.id, segments.filter { it.launchId == last.launchId }.map { it.id })
    }

    private fun ensureOpen(session: Session, launchId: String?) {
        if (launchId != null && session.launchId != launchId) throw NotYourSessionException()
        if (session.endedAt != null) throw SessionClosedException(session)
    }

    override suspend fun agentOf(orgId: Uuid, sessionId: String): String? = repository.get(orgId, sessionId)?.agent
}
