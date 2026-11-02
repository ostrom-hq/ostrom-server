// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gates.domain.usecases

import dev.ostrom.server.gates.domain.models.ActorKind
import dev.ostrom.server.gates.domain.models.Gate
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.domain.models.GateNotFoundException
import dev.ostrom.server.gates.domain.models.GateNotUsableException
import dev.ostrom.server.gates.domain.models.GateStatus
import dev.ostrom.server.gates.domain.models.NotYourGateException
import dev.ostrom.server.gates.domain.repositories.GatesRepository
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The three moves of a yes around the action it covers. */
enum class GateUse {
    /**
     * Before the action (PreToolUse): the approved yes is held by the session. Two parallel tool calls can no longer
     * both see "approved" and both run (audit A24): the second one is refused.
     */
    RESERVE,

    /**
     * After the action ran (PostToolUse): the yes is used up; the same action again asks a new yes. A hook that did not
     * reserve consumes the approved yes directly (contract `consumeGate`), still exactly once. Nothing to use on a
     * shadow or ungated gate: it is returned as it is.
     */
    CONSUME,

    /** The action did not run after all (the agent's own permission check refused it): the yes is approved again. */
    RELEASE,
}

/** Reserves, consumes or releases a yes. Exactly once each, even under parallel calls. */
interface UseGateUseCase {
    /**
     * Moves a gate's yes.
     *
     * @param actor The agent session that asked for the gate.
     * @param gateId The gate.
     * @param use The move.
     * @return The gate after the move.
     * @throws GateNotFoundException When the gate is not in the caller's organization.
     * @throws NotYourGateException When the caller is not a session that asked for this gate.
     * @throws GateNotUsableException When the move is not possible from the gate's state.
     */
    suspend operator fun invoke(actor: GateActor, gateId: Uuid, use: GateUse): Gate
}

/**
 * [UseGateUseCase] on the repository, under the gate's row lock. A yes is used only once its receipt is signed
 * (SECURITY-AUDIT S2-14): it is signed now if it was not yet, and refused while it cannot be (fail closed).
 *
 * @param repository The gates.
 * @param receipts Signs a decided gate's receipt.
 * @param clock The clock.
 */
class UseGateUseCaseImpl(
    private val repository: GatesRepository,
    private val receipts: EnsureGateReceiptUseCase,
    private val clock: Clock,
) : UseGateUseCase {
    override suspend fun invoke(actor: GateActor, gateId: Uuid, use: GateUse): Gate {
        // Signing never holds the row lock; the transaction below re-reads the gate with its receipt.
        if (use != GateUse.RELEASE) repository.get(actor.orgId, gateId)?.let { receipts.ensureSigned(it) }
        return move(actor, gateId, use)
    }

    private suspend fun move(actor: GateActor, gateId: Uuid, use: GateUse): Gate = repository.transaction {
        val gate = lockGate(actor.orgId, gateId) ?: throw GateNotFoundException()
        val launch = launchOf(actor, gate)
        val now = clock.now()
        val next = when (use) {
            GateUse.RESERVE -> reserved(gate, launch, now)
            GateUse.CONSUME -> consumed(gate, launch, now)
            GateUse.RELEASE -> released(gate, launch, now)
        }
        if (next != gate) updateGate(next)
        next
    }

    // Only an agent session of a launch that asked for the gate moves its yes.
    private fun launchOf(actor: GateActor, gate: Gate): String {
        val launch = actor.launchId
        if (actor.kind != ActorKind.AGENT_SESSION || launch == null || launch !in gate.launchIds) {
            throw NotYourGateException()
        }
        return launch
    }

    private fun reserved(gate: Gate, launch: String, now: Instant): Gate {
        if (gate.status != GateStatus.APPROVED) throw GateNotUsableException(reserveRefusal(gate))
        usable(gate, now)
        return gate.copy(status = GateStatus.RESERVED, reservedBy = launch, reservedAt = now)
    }

    // A shadow or ungated action has no yes to use: the gate stays as it is.
    private fun consumed(gate: Gate, launch: String, now: Instant): Gate = when {
        gate.status == GateStatus.SHADOW || gate.status == GateStatus.UNGATED -> gate
        gate.status == GateStatus.APPROVED -> usable(gate, now).copy(status = GateStatus.CONSUMED, consumedAt = now)
        else -> {
            heldBy(gate, launch)
            gate.copy(status = GateStatus.CONSUMED, consumedAt = now)
        }
    }

    // An approved yes covers its action only signed and within its lifetime.
    private fun usable(gate: Gate, now: Instant): Gate {
        if (gate.receiptId == null) throw GateNotUsableException(UNSIGNED)
        if (gate.yesExpired(now)) throw GateNotUsableException(EXPIRED)
        return gate
    }

    // A yes goes back in play once: a second release would let reserve, launch and release loop until it expires.
    private fun released(gate: Gate, launch: String, now: Instant): Gate {
        heldBy(gate, launch)
        if (gate.releasedAt != null) throw GateNotUsableException(RELEASED)
        return gate.copy(status = GateStatus.APPROVED, reservedBy = null, reservedAt = null, releasedAt = now)
    }

    private fun heldBy(gate: Gate, launch: String) {
        if (gate.status == GateStatus.RESERVED && gate.reservedBy == launch) return
        throw GateNotUsableException(
            if (gate.status == GateStatus.APPROVED) {
                "This yes is not reserved by your session: reserve it before the action."
            } else {
                reserveRefusal(gate)
            },
        )
    }

    private fun reserveRefusal(gate: Gate): String = when (gate.status) {
        GateStatus.RESERVED -> "This yes is already in use by another action. Ask again for this one."
        GateStatus.CONSUMED -> "This yes was already used. Ask again: one yes covers one action."
        GateStatus.PENDING -> "This gate is still waiting for a yes."
        GateStatus.REJECTED -> "This gate was refused."
        else -> "This action is not gated: there is no yes to reserve."
    }

    private companion object {
        const val EXPIRED = "This yes expired: it waits 15 minutes for its action. Ask again."
        const val RELEASED = "This yes was already released once: use it for this action, or ask again."
        const val UNSIGNED = "This yes has no signed receipt yet: it cannot be used. Try again in a moment."
    }
}
