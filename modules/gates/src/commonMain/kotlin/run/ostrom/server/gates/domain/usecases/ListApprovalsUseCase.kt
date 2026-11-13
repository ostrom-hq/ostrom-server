// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gates.domain.usecases

import run.ostrom.server.gates.domain.models.ActorKind
import run.ostrom.server.gates.domain.models.ApprovalView
import run.ostrom.server.gates.domain.models.GateActor
import run.ostrom.server.gates.domain.models.OnlyPeopleAnswerException
import run.ostrom.server.gates.domain.models.RequestStatus
import run.ostrom.server.gates.domain.repositories.GatesRepository
import run.ostrom.server.kernel.errors.Pages
import kotlin.uuid.Uuid

/** The approvals a person reads: their inbox, or the organization's recent approvals (the console). */
interface ListApprovalsUseCase {
    /**
     * Lists approvals with their gates, newest first.
     *
     * @param actor A signed-in person.
     * @param mine True for the caller's approvals only.
     * @param status Only this status, or any when null.
     * @param limit At most this many, capped at [MAX_LIMIT].
     * @param before The `next` of the previous page (a request's id), or null for the newest.
     * @return The approvals.
     * @throws OnlyPeopleAnswerException When the caller is not a person.
     * @throws run.ostrom.server.kernel.errors.InvalidPageException When [limit] is under one, or [before] names no
     *   request of the organization.
     */
    suspend operator fun invoke(
        actor: GateActor,
        mine: Boolean,
        status: RequestStatus?,
        limit: Int,
        before: Uuid? = null,
    ): List<ApprovalView>

    /** The page's bounds. */
    companion object {
        /** The largest page. */
        const val MAX_LIMIT: Int = 100
    }
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
        before: Uuid?,
    ): List<ApprovalView> {
        if (actor.kind != ActorKind.USER) throw OnlyPeopleAnswerException()
        // Second audit L4: requests name people, so "mine" is the caller's person, not its credential.
        val person = actor.personId ?: actor.id
        val size = Pages.size(limit, ListApprovalsUseCase.MAX_LIMIT, ListApprovalsUseCase.MAX_LIMIT)
        val page = repository.approvals(actor.orgId, person.takeIf { mine }, status, size, before)
        // A `before` that names no request of the organization (forged, or another's) is a 400, not an empty page.
        return Pages.checked("before", before, page) { repository.approval(actor.orgId, it) != null }
    }
}
