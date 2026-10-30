// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gates.domain.usecases

import dev.ostrom.server.gates.domain.models.ActorKind
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.domain.models.GateNotFoundException
import dev.ostrom.server.gates.domain.models.GateView
import dev.ostrom.server.gates.domain.models.NotYourGateException
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
