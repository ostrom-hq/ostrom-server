// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gates.domain.usecases

import dev.ostrom.server.gates.domain.models.ActorKind
import dev.ostrom.server.gates.domain.models.Answer
import dev.ostrom.server.gates.domain.models.AnswerChannel
import dev.ostrom.server.gates.domain.models.ApprovalRequest
import dev.ostrom.server.gates.domain.models.Gate
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.domain.models.GateAlreadyDecidedException
import dev.ostrom.server.gates.domain.models.GateChangedException
import dev.ostrom.server.gates.domain.models.GateNotFoundException
import dev.ostrom.server.gates.domain.models.GateStatus
import dev.ostrom.server.gates.domain.models.GateView
import dev.ostrom.server.gates.domain.models.NotTheApproverException
import dev.ostrom.server.gates.domain.models.OnlyPeopleAnswerException
import dev.ostrom.server.gates.domain.models.RequestStatus
import dev.ostrom.server.gates.domain.models.SelfApproval
import dev.ostrom.server.gates.domain.models.SelfApprovalRefusedException
import dev.ostrom.server.gates.domain.repositories.GatesRepository
import dev.ostrom.server.gates.domain.services.GateNotifier
import dev.ostrom.server.gates.domain.services.GatePolicies
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** One approver answers the request a gate sent them. */
interface AnswerGateUseCase {
    /**
     * Answers a request. The first yes opens the gate and closes the other approvers' requests in the same
     * transaction; a no refuses the gate and closes them too (any approver may veto). The decision is then signed.
     *
     * @param actor The caller: a signed-in person, the approver of this request.
     * @param requestId The request.
     * @param answer Yes or no.
     * @param shownDigest The digest of what the approver was shown; a yes on anything else is refused.
     * @param channel Where the answer was given.
     * @return The gate as it is after the answer, with its receipt when signing succeeded.
     * @throws OnlyPeopleAnswerException When the caller is a device, agent or service.
     * @throws GateNotFoundException When the request does not exist in the caller's organization.
     * @throws NotTheApproverException When the caller is not this request's approver (an admin included).
     * @throws SelfApprovalRefusedException When the caller asked for the action and someone else must say yes.
     * @throws GateAlreadyDecidedException When the gate or the request was already answered.
     * @throws GateChangedException When what the gate covers is not what the approver was shown.
     */
    suspend operator fun invoke(
        actor: GateActor,
        requestId: Uuid,
        answer: Answer,
        shownDigest: String,
        channel: AnswerChannel,
    ): GateView
}

/**
 * [AnswerGateUseCase] on the repository: the gate row is locked for the decision.
 *
 * @param repository The gates.
 * @param policies The organization's self-approval setting.
 * @param receipts Signs the decision.
 * @param notifier Tells the other approvers, the GitHub check and the waiting hooks; it never throws.
 * @param clock The clock.
 */
class AnswerGateUseCaseImpl(
    private val repository: GatesRepository,
    private val policies: GatePolicies,
    private val receipts: EnsureGateReceiptUseCase,
    private val notifier: GateNotifier,
    private val clock: Clock,
) : AnswerGateUseCase {
    override suspend fun invoke(
        actor: GateActor,
        requestId: Uuid,
        answer: Answer,
        shownDigest: String,
        channel: AnswerChannel,
    ): GateView {
        // S2-15: the answerer is the credential's person, never its id.
        val person = actor.personId
        if (actor.kind != ActorKind.USER || person == null) throw OnlyPeopleAnswerException()
        val orgId = actor.orgId
        val policy = policies.selfApproval(orgId)
        val decided = repository.transaction {
            val request = request(orgId, requestId) ?: throw GateNotFoundException()
            val gate = lockGate(orgId, request.gateId) ?: throw GateNotFoundException()
            checkAnswerable(person, request, gate, shownDigest)
            val requests = requestsOf(orgId, gate.id)
            val verdict = SelfApproval.check(gate.askedBy, requests.map { it.approverId }, person, answer, policy)
            val selfApproved = when (verdict) {
                is SelfApproval.Verdict.Refused -> throw SelfApprovalRefusedException(verdict.others)
                is SelfApproval.Verdict.Allowed -> verdict.selfApproved
            }
            val now = clock.now()
            val answered = answered(requests, request.id, answer, channel, now)
            val next = gate.copy(
                status = if (answer == Answer.YES) GateStatus.APPROVED else GateStatus.REJECTED,
                decidedBy = request.approverId,
                decidedAt = now,
                selfApproved = selfApproved,
            )
            updateGate(next)
            updateRequests(answered)
            next to answered
        }
        val requests = decided.second
        val gate = receipts.ensureSigned(decided.first)
        notifier.decided(gate, requests)
        return viewOf(gate, requests, policy)
    }

    private fun checkAnswerable(person: String, request: ApprovalRequest, gate: Gate, shownDigest: String) {
        val refused = when {
            !SelfApproval.same(request.approverId, person) -> NotTheApproverException()
            gate.status != GateStatus.PENDING -> GateAlreadyDecidedException()
            request.status != RequestStatus.PENDING -> GateAlreadyDecidedException()
            shownDigest != gate.shownDigest -> GateChangedException()
            else -> null
        }
        if (refused != null) throw refused
    }

    // The gate is pending, so every request is: the answered one takes the answer, the others are closed.
    private fun answered(
        requests: List<ApprovalRequest>,
        answeredId: Uuid,
        answer: Answer,
        channel: AnswerChannel,
        now: Instant,
    ): List<ApprovalRequest> = requests.map {
        if (it.id == answeredId) {
            it.copy(
                status = if (answer == Answer.YES) RequestStatus.APPROVED else RequestStatus.REJECTED,
                answeredAt = now,
                channel = channel,
            )
        } else {
            it.copy(status = RequestStatus.CLOSED, answeredAt = now)
        }
    }
}
