package dev.ostrom.server.gates.domain.usecases

import dev.ostrom.server.gates.domain.models.ActorKind
import dev.ostrom.server.gates.domain.models.ApprovalRequest
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.domain.models.GateNotFoundException
import dev.ostrom.server.gates.domain.models.GateView
import dev.ostrom.server.gates.domain.models.NotYourGateException
import dev.ostrom.server.gates.domain.models.OnlyPeopleAnswerException
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
        if (actor.kind == ActorKind.AGENT_SESSION && actor.sessionId !in stored.sessionIds) throw NotYourGateException()
        val gate = receipts.ensureSigned(stored)
        return viewOf(gate, repository.requestsOf(actor.orgId, gate.id), policies.selfApproval(actor.orgId))
    }
}

/** The approver's inbox: the requests waiting for their answer. */
interface ListPendingRequestsUseCase {
    /**
     * Lists the caller's pending requests, newest first.
     *
     * @param actor A signed-in person.
     * @param limit At most this many, capped at 100.
     * @return The requests.
     * @throws OnlyPeopleAnswerException When the caller is not a person.
     */
    suspend operator fun invoke(actor: GateActor, limit: Int): List<ApprovalRequest>
}

/**
 * [ListPendingRequestsUseCase] on the repository.
 *
 * @param repository The gates.
 */
class ListPendingRequestsUseCaseImpl(
    private val repository: GatesRepository,
) : ListPendingRequestsUseCase {
    override suspend fun invoke(actor: GateActor, limit: Int): List<ApprovalRequest> {
        if (actor.kind != ActorKind.USER) throw OnlyPeopleAnswerException()
        return repository.pendingFor(actor.orgId, actor.id, limit.coerceIn(1, MAX_LIMIT))
    }

    private companion object {
        const val MAX_LIMIT = 100
    }
}
