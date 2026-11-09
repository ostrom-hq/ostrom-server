// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.gates.domain.usecases

import run.ostrom.server.gates.domain.models.ActorKind
import run.ostrom.server.gates.domain.models.ApprovalView
import run.ostrom.server.gates.domain.models.GateActor
import run.ostrom.server.gates.domain.models.GateNotFoundException
import run.ostrom.server.gates.domain.models.OnlyPeopleAnswerException
import run.ostrom.server.gates.domain.models.SelfApproval
import run.ostrom.server.gates.domain.repositories.GatesRepository
import kotlin.uuid.Uuid

/** One approval with what it shows: the yes page, the console, the app. */
interface GetApprovalUseCase {
    /**
     * Reads an approval.
     *
     * @param actor A signed-in person of the organization.
     * @param approvalId The approval, or its gate: the reader's own request on that gate, else its first.
     * @return The approval and its gate; its id is the request's.
     * @throws OnlyPeopleAnswerException When the caller is not a person.
     * @throws GateNotFoundException When absent or another organization's.
     */
    suspend operator fun invoke(actor: GateActor, approvalId: Uuid): ApprovalView
}

/**
 * [GetApprovalUseCase] on the repository.
 *
 * @param repository The gates.
 */
class GetApprovalUseCaseImpl(
    private val repository: GatesRepository,
) : GetApprovalUseCase {
    override suspend fun invoke(actor: GateActor, approvalId: Uuid): ApprovalView {
        if (actor.kind != ActorKind.USER) throw OnlyPeopleAnswerException()
        return repository.approvalOrGateRequest(actor.orgId, approvalId, actor.personId ?: actor.id)
            ?: throw GateNotFoundException()
    }
}

/**
 * Reads an approval by its id or by its gate's: a session links each of its gates by the gate's id (PARCOURS 3.3).
 * A gate's id gives the reader's own request on it (login, case ignored), else its first request.
 *
 * @param orgId The organization.
 * @param id An approval's id, or a gate's.
 * @param person Who reads.
 * @return The approval, or null when neither is the organization's.
 */
internal suspend fun GatesRepository.approvalOrGateRequest(orgId: Uuid, id: Uuid, person: String): ApprovalView? {
    approval(orgId, id)?.let { return it }
    val requests = requestsOf(orgId, id)
    val request = requests.firstOrNull { SelfApproval.same(it.approverId, person) } ?: requests.firstOrNull()
    return request?.let { approval(orgId, it.id) }
}
