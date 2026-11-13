// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gates.domain.usecases

import run.ostrom.server.gates.domain.models.ActorKind
import run.ostrom.server.gates.domain.models.Gate
import run.ostrom.server.gates.domain.models.GateActor
import run.ostrom.server.gates.domain.models.GateNotFoundException
import run.ostrom.server.gates.domain.models.GateStatus
import run.ostrom.server.gates.domain.models.GateView
import run.ostrom.server.gates.domain.models.NotYourGateException
import run.ostrom.server.gates.domain.repositories.GatesRepository
import run.ostrom.server.gates.domain.services.GateChanges
import run.ostrom.server.gates.domain.services.GatePolicies
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

/** Reads one gate: the hook waiting for its answer, the yes page, the console. */
interface GetGateUseCase {
    /**
     * Reads a gate, signing its receipt first if the decision has none yet.
     *
     * @param actor The caller. An agent session reads only the gates it asked for.
     * @param gateId The gate.
     * @param wait How long a pending gate may be waited on for its answer, capped at [MAX_WAIT]: the read answers as
     *   soon as the gate is no longer pending, or with it pending at the end. Zero reads at once.
     * @return The gate, its requests and who it waits for.
     * @throws GateNotFoundException When absent or another organization's.
     * @throws NotYourGateException When an agent session reads another session's gate.
     */
    suspend operator fun invoke(actor: GateActor, gateId: Uuid, wait: Duration = Duration.ZERO): GateView

    /** Bounds of a waiting read. */
    companion object {
        /** The longest wait: a hook asks again after it, so no connection waits on a gate for long. */
        val MAX_WAIT: Duration = 30.seconds

        /** How often a waiting read looks at the database for an answer given on another instance. */
        val LOOK_EVERY: Duration = 2.seconds
    }
}

/**
 * [GetGateUseCase] on the repository.
 *
 * @param repository The gates.
 * @param policies The self-approval setting (for who the gate waits for).
 * @param receipts Signs a missing receipt.
 * @param changes Wakes a waiting read when the gate is answered on this instance.
 * @param time Measures the wait.
 */
class GetGateUseCaseImpl(
    private val repository: GatesRepository,
    private val policies: GatePolicies,
    private val receipts: EnsureGateReceiptUseCase,
    private val changes: GateChanges,
    private val time: TimeSource = TimeSource.Monotonic,
) : GetGateUseCase {
    override suspend fun invoke(actor: GateActor, gateId: Uuid, wait: Duration): GateView {
        // The first read checks the organization and the launch before anything waits: another's gate is a 404 now.
        var stored = read(actor, gateId)
        val until = time.markNow() + wait.coerceIn(Duration.ZERO, GetGateUseCase.MAX_WAIT)
        // Each look is a short read of its own: between two, the wait holds no connection.
        while (stored.status == GateStatus.PENDING && until.hasNotPassedNow()) {
            changes.await(gateId, minOf(-until.elapsedNow(), GetGateUseCase.LOOK_EVERY))
            stored = read(actor, gateId)
        }
        val gate = receipts.ensureSigned(stored)
        return viewOf(gate, repository.requestsOf(actor.orgId, gate.id)) { policies.selfApproval(actor.orgId) }
    }

    private suspend fun read(actor: GateActor, gateId: Uuid): Gate {
        val stored = repository.get(actor.orgId, gateId) ?: throw GateNotFoundException()
        if (actor.kind == ActorKind.AGENT_SESSION && actor.launchId !in stored.launchIds) throw NotYourGateException()
        return stored
    }
}
