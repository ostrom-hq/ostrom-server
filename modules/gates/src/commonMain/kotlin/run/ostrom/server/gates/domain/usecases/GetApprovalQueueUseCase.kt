// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.gates.domain.usecases

import run.ostrom.server.gates.domain.models.ActorKind
import run.ostrom.server.gates.domain.models.Answer
import run.ostrom.server.gates.domain.models.ApprovalInbox
import run.ostrom.server.gates.domain.models.ApprovalRights
import run.ostrom.server.gates.domain.models.ApprovalScreen
import run.ostrom.server.gates.domain.models.ApprovalView
import run.ostrom.server.gates.domain.models.GateActor
import run.ostrom.server.gates.domain.models.OnlyPeopleAnswerException
import run.ostrom.server.gates.domain.models.RequestStatus
import run.ostrom.server.gates.domain.models.SelfApproval
import run.ostrom.server.gates.domain.models.Viewer
import run.ostrom.server.gates.domain.repositories.GatesRepository
import run.ostrom.server.gates.domain.services.GateGitHubAccounts
import run.ostrom.server.gates.domain.services.GatePolicies

/** The yes queue of a person, as the console and the app open on it. */
interface GetApprovalQueueUseCase {
    /**
     * Reads what waits for the caller's answer and what they were asked lately, each with whether they may say yes.
     *
     * @param actor A signed-in person of the organization.
     * @param viewer Where they read it from.
     * @return Their pending requests (at most 100) and their last answered or closed ones (at most 8), newest first.
     * @throws OnlyPeopleAnswerException When the caller is not a person.
     */
    suspend operator fun invoke(actor: GateActor, viewer: Viewer): ApprovalInbox
}

/**
 * [GetApprovalQueueUseCase] on the repository: the inbox reads of [GatesRepository.approvals], then one read of the
 * approvers of every gate shown.
 *
 * @param repository The gates.
 * @param policies The organization's self-approval setting.
 * @param gitHub The GitHub account the reader linked (Q-GATES-G1).
 */
class GetApprovalQueueUseCaseImpl(
    private val repository: GatesRepository,
    private val policies: GatePolicies,
    private val gitHub: GateGitHubAccounts,
) : GetApprovalQueueUseCase {
    override suspend fun invoke(actor: GateActor, viewer: Viewer): ApprovalInbox {
        // S2-15: the queue is the credential's person's, never its id's.
        val person = actor.personId
        if (actor.kind != ActorKind.USER || person == null) throw OnlyPeopleAnswerException()
        val orgId = actor.orgId
        val pending = repository.approvals(orgId, person, RequestStatus.PENDING, PENDING_LIMIT)
        // One inbox read per answered status (the inbox index), merged newest first.
        val answered = listOf(RequestStatus.APPROVED, RequestStatus.REJECTED, RequestStatus.CLOSED)
        val recent = answered.flatMap { repository.approvals(orgId, person, it, RECENT_LIMIT) }
            .sortedByDescending { it.request.createdAt }
            .take(RECENT_LIMIT)
        val approvers = repository.approversOf(orgId, (pending + recent).map { it.gate.id }.distinct())
        val policy = policies.selfApproval(orgId)
        // The reader's GitHub account, read once and only for a pull request whose author is linked to nobody.
        val linking = (pending + recent).any { SelfApproval.needsLink(it.gate, Answer.YES, policy) }
        val gitHubIds = if (linking) gitHub.gitHubIdsOf(person) else emptyList()
        val screen = { view: ApprovalView ->
            val gateApprovers = approvers.getValue(view.gate.id)
            val rights = ApprovalRights.of(person, viewer, view, gateApprovers, policy)
            val linked = if (SelfApproval.needsLink(view.gate, Answer.YES, policy)) {
                ApprovalRights.linked(rights, view.gate, gateApprovers, gitHubIds)
            } else {
                rights
            }
            ApprovalScreen(view, linked)
        }
        return ApprovalInbox(person, viewer, pending.map(screen), recent.map(screen))
    }

    private companion object {
        const val PENDING_LIMIT = 100
        const val RECENT_LIMIT = 8
    }
}
