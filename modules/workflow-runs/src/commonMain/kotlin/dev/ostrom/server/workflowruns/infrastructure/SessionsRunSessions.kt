// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.workflowruns.infrastructure

import dev.ostrom.server.gates.domain.usecases.ListSessionGatesUseCase
import dev.ostrom.server.gates.domain.usecases.SessionGate
import dev.ostrom.server.sessions.domain.models.Session
import dev.ostrom.server.sessions.domain.repositories.SessionsRepository
import dev.ostrom.server.workflowruns.domain.services.RunSessions
import kotlin.uuid.Uuid

/**
 * [RunSessions] on the sessions module's sessions and the gates module's gates of a session.
 *
 * @param sessions The sessions.
 * @param sessionGates The gates of a session.
 */
class SessionsRunSessions(
    private val sessions: SessionsRepository,
    private val sessionGates: ListSessionGatesUseCase,
) : RunSessions {
    override suspend fun find(orgId: Uuid, id: String): Session? = sessions.get(orgId, id)

    override suspend fun gates(orgId: Uuid, id: String): List<SessionGate> = sessionGates(orgId, id)
}
