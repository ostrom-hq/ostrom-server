// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.sessions.infrastructure

import dev.ostrom.server.gates.domain.services.GateSessions
import dev.ostrom.server.sessions.domain.models.NotYourSessionException
import dev.ostrom.server.sessions.domain.models.SessionClosedException
import dev.ostrom.server.sessions.domain.models.SessionNotFoundException
import dev.ostrom.server.sessions.domain.repositories.SessionsRepository
import kotlin.uuid.Uuid

/**
 * The sessions' side of a gate: a gate is asked only in an open session (CORRECTIFS-4: a closed session's receipt is
 * signed, nothing more is added to it), and an agent token asks only in its own launch's sessions (audit A12).
 *
 * @param repository The sessions.
 */
class SessionsGateGuard(private val repository: SessionsRepository) : GateSessions {
    override suspend fun assertOpen(orgId: Uuid, sessionId: String, launchId: String?) {
        val session = repository.get(orgId, sessionId) ?: throw SessionNotFoundException()
        if (launchId != null && session.launchId != launchId) throw NotYourSessionException()
        if (session.endedAt != null) throw SessionClosedException(session)
    }

    override suspend fun agentOf(orgId: Uuid, sessionId: String): String? = repository.get(orgId, sessionId)?.agent
}
