// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.gates.domain.usecases

import run.ostrom.server.gates.domain.repositories.GatesRepository
import kotlin.uuid.Uuid

/**
 * The approvers of a session's gates: the people a gate of the session was sent to, whatever they answered. Beside the
 * session's person and the organization's owners and administrators, they read what the session did for them to judge
 * (its receipts, its checks and notifications), never more (TeamCode #238, parity TC-NEW-675a530-2).
 */
interface SessionApproversUseCase {
    /**
     * Whether [person] approves a gate of [sessionId].
     *
     * @param orgId The organization.
     * @param person The person's login, any case.
     * @param sessionId The session segment.
     *
     * @return True when a gate of the session was sent to them.
     */
    suspend fun approves(orgId: Uuid, person: String, sessionId: String): Boolean

    /**
     * The sessions whose gates [person] approves.
     *
     * @param orgId The organization.
     * @param person The person's login, any case.
     *
     * @return The sessions, each once.
     */
    suspend operator fun invoke(orgId: Uuid, person: String): List<String>
}

/**
 * [SessionApproversUseCase] on the gates' requests.
 *
 * @param repository The gates.
 */
class SessionApproversUseCaseImpl(private val repository: GatesRepository) : SessionApproversUseCase {
    override suspend fun approves(orgId: Uuid, person: String, sessionId: String): Boolean =
        repository.sessionsApprovedBy(orgId, person, sessionId).isNotEmpty()

    override suspend fun invoke(orgId: Uuid, person: String): List<String> =
        repository.sessionsApprovedBy(orgId, person)
}
