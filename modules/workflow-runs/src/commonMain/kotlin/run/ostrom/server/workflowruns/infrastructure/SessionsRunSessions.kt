// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.workflowruns.infrastructure

import run.ostrom.server.gates.domain.usecases.ListSessionGatesUseCase
import run.ostrom.server.gates.domain.usecases.SessionApproversUseCase
import run.ostrom.server.gates.domain.usecases.SessionGate
import run.ostrom.server.sessions.domain.models.Session
import run.ostrom.server.sessions.domain.repositories.SessionsRepository
import run.ostrom.server.workflowruns.domain.services.RunSessions
import kotlin.uuid.Uuid

/**
 * [RunSessions] on the sessions module's sessions and the gates module's gates of a session and their approvers.
 *
 * @param sessions The sessions.
 * @param sessionGates The gates of a session.
 * @param approvers The approvers of the sessions' gates.
 */
class SessionsRunSessions(
    private val sessions: SessionsRepository,
    private val sessionGates: ListSessionGatesUseCase,
    private val approvers: SessionApproversUseCase,
) : RunSessions {
    override suspend fun find(orgId: Uuid, id: String): Session? = sessions.get(orgId, id)

    override suspend fun gates(orgId: Uuid, id: String): List<SessionGate> = sessionGates(orgId, id)

    override suspend fun approves(orgId: Uuid, person: String, id: String): Boolean =
        approvers.approves(orgId, person, id)

    override suspend fun approvedBy(orgId: Uuid, person: String): List<String> = approvers(orgId, person)
}
