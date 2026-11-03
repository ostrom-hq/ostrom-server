// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gates.domain.usecases

import dev.ostrom.server.gates.domain.models.ActorKind
import dev.ostrom.server.gates.domain.models.ApprovalView
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.domain.models.OnlyPeopleAnswerException
import dev.ostrom.server.gates.domain.models.RequestStatus
import dev.ostrom.server.gates.domain.repositories.GatesRepository
import dev.ostrom.server.kernel.errors.Pages

/** The approvals a person reads: their inbox, or the organization's recent approvals (the console). */
interface ListApprovalsUseCase {
    /**
     * Lists approvals with their gates, newest first.
     *
     * @param actor A signed-in person.
     * @param mine True for the caller's approvals only.
     * @param status Only this status, or any when null.
     * @param limit At most this many, capped at 100.
     * @return The approvals.
     * @throws OnlyPeopleAnswerException When the caller is not a person.
     */
    suspend operator fun invoke(actor: GateActor, mine: Boolean, status: RequestStatus?, limit: Int): List<ApprovalView>
}

/**
 * [ListApprovalsUseCase] on the repository.
 *
 * @param repository The gates.
 */
class ListApprovalsUseCaseImpl(
    private val repository: GatesRepository,
) : ListApprovalsUseCase {
    override suspend fun invoke(
        actor: GateActor,
        mine: Boolean,
        status: RequestStatus?,
        limit: Int,
    ): List<ApprovalView> {
        if (actor.kind != ActorKind.USER) throw OnlyPeopleAnswerException()
        // Second audit L4: requests name people, so "mine" is the caller's person, not its credential.
        val person = actor.personId ?: actor.id
        return repository.approvals(actor.orgId, person.takeIf { mine }, status, Pages.size(limit, MAX_LIMIT, MAX_LIMIT))
    }

    private companion object {
        const val MAX_LIMIT = 100
    }
}
