// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gates.domain.usecases

import dev.ostrom.server.gates.domain.models.ActorKind
import dev.ostrom.server.gates.domain.models.ApprovalView
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.domain.models.GateNotFoundException
import dev.ostrom.server.gates.domain.models.OnlyPeopleAnswerException
import dev.ostrom.server.gates.domain.repositories.GatesRepository
import kotlin.uuid.Uuid

/** One approval with what it shows: the yes page, the console, the app. */
interface GetApprovalUseCase {
    /**
     * Reads an approval.
     *
     * @param actor A signed-in person of the organization.
     * @param approvalId The approval.
     * @return The approval and its gate.
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
        return repository.approval(actor.orgId, approvalId) ?: throw GateNotFoundException()
    }
}
