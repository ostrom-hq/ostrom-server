package dev.ostrom.server.gates.domain.usecases

import dev.ostrom.server.gates.domain.models.ActorKind
import dev.ostrom.server.gates.domain.models.ApprovalView
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.domain.models.GateNotFoundException
import dev.ostrom.server.gates.domain.models.GateView
import dev.ostrom.server.gates.domain.models.NotYourGateException
import dev.ostrom.server.gates.domain.models.OnlyPeopleAnswerException
import dev.ostrom.server.gates.domain.models.RequestStatus
import dev.ostrom.server.gates.domain.repositories.GatesRepository
import dev.ostrom.server.gates.domain.services.GatePolicies
import kotlin.uuid.Uuid

/** Reads one gate: the hook waiting for its answer, the yes page, the console. */
interface GetGateUseCase {
    /**
     * Reads a gate, signing its receipt first if the decision has none yet.
     *
     * @param actor The caller. An agent session reads only the gates it asked for.
     * @param gateId The gate.
     * @return The gate, its requests and who it waits for.
     * @throws GateNotFoundException When absent or another organization's.
     * @throws NotYourGateException When an agent session reads another session's gate.
     */
    suspend operator fun invoke(actor: GateActor, gateId: Uuid): GateView
}

/**
 * [GetGateUseCase] on the repository.
 *
 * @param repository The gates.
 * @param policies The self-approval setting (for who the gate waits for).
 * @param receipts Signs a missing receipt.
 */
class GetGateUseCaseImpl(
    private val repository: GatesRepository,
    private val policies: GatePolicies,
    private val receipts: EnsureGateReceiptUseCase,
) : GetGateUseCase {
    override suspend fun invoke(actor: GateActor, gateId: Uuid): GateView {
        val stored = repository.get(actor.orgId, gateId) ?: throw GateNotFoundException()
        if (actor.kind == ActorKind.AGENT_SESSION && actor.launchId !in stored.launchIds) throw NotYourGateException()
        val gate = receipts.ensureSigned(stored)
        return viewOf(gate, repository.requestsOf(actor.orgId, gate.id), policies.selfApproval(actor.orgId))
    }
}

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
        return repository.approvals(actor.orgId, actor.id.takeIf { mine }, status, limit.coerceIn(1, MAX_LIMIT))
    }

    private companion object {
        const val MAX_LIMIT = 100
    }
}

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
