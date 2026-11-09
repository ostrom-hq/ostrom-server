// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.gates.domain.usecases

import run.ostrom.server.gates.domain.models.ActorKind
import run.ostrom.server.gates.domain.models.Answer
import run.ostrom.server.gates.domain.models.ApprovalRights
import run.ostrom.server.gates.domain.models.ApprovalScreen
import run.ostrom.server.gates.domain.models.GateActor
import run.ostrom.server.gates.domain.models.GateNotFoundException
import run.ostrom.server.gates.domain.models.OnlyPeopleAnswerException
import run.ostrom.server.gates.domain.models.SelfApproval
import run.ostrom.server.gates.domain.models.Viewer
import run.ostrom.server.gates.domain.repositories.GatesRepository
import run.ostrom.server.gates.domain.services.GateGitHubAccounts
import run.ostrom.server.gates.domain.services.GatePolicies
import kotlin.uuid.Uuid

/** One approval as the console and the app show it: the request, its gate, and whether the reader may say yes. */
interface GetApprovalViewUseCase {
    /**
     * Reads an approval for the person who opens it.
     *
     * @param actor A signed-in person of the organization.
     * @param viewer Where they read it from.
     * @param approvalId The approval, or its gate: a session links each of its gates by the gate's id, and the reader
     *   then sees their own request on that gate, else its first.
     * @return The approval, its gate and the reader's rights.
     * @throws OnlyPeopleAnswerException When the caller is not a person.
     * @throws GateNotFoundException When absent or another organization's.
     */
    suspend operator fun invoke(actor: GateActor, viewer: Viewer, approvalId: Uuid): ApprovalScreen
}

/**
 * [GetApprovalViewUseCase] on the repository.
 *
 * @param repository The gates.
 * @param policies The organization's self-approval setting.
 * @param gitHub The GitHub account the reader linked (Q-GATES-G1).
 */
class GetApprovalViewUseCaseImpl(
    private val repository: GatesRepository,
    private val policies: GatePolicies,
    private val gitHub: GateGitHubAccounts,
) : GetApprovalViewUseCase {
    override suspend fun invoke(actor: GateActor, viewer: Viewer, approvalId: Uuid): ApprovalScreen {
        // S2-15: a yes is signed as the credential's person, never as its id.
        val person = actor.personId
        if (actor.kind != ActorKind.USER || person == null) throw OnlyPeopleAnswerException()
        val view = repository.approvalOrGateRequest(actor.orgId, approvalId, person) ?: throw GateNotFoundException()
        // The approval is one of its gate's requests: the gate always has approvers.
        val approvers = repository.approversOf(actor.orgId, listOf(view.gate.id)).getValue(view.gate.id)
        val policy = policies.selfApproval(actor.orgId)
        val rights = ApprovalRights.of(person, viewer, view, approvers, policy)
        if (!SelfApproval.needsLink(view.gate, Answer.YES, policy)) return ApprovalScreen(view, rights)
        return ApprovalScreen(view, ApprovalRights.linked(rights, view.gate, approvers, gitHub.gitHubIdsOf(person)))
    }
}
