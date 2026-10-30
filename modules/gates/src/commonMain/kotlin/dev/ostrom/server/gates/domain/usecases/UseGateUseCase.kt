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
import kotlin.uuid.Uuid

/** The three moves of a yes around the action it covers. */
enum class GateUse {
    /**
     * Before the action (PreToolUse): the approved yes is held by the session. Two parallel tool calls can no longer
     * both see "approved" and both run (audit A24): the second one is refused.
     */
    RESERVE,

    /** After the action ran (PostToolUse): the yes is used up; the same action again asks a new yes. */
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
 * [UseGateUseCase] on the repository, under the gate's row lock.
 *
 * @param repository The gates.
 * @param clock The clock.
 */
class UseGateUseCaseImpl(
    private val repository: GatesRepository,
    private val clock: Clock,
) : UseGateUseCase {
    override suspend fun invoke(actor: GateActor, gateId: Uuid, use: GateUse): Gate = repository.transaction {
        val gate = lockGate(actor.orgId, gateId) ?: throw GateNotFoundException()
        val session = actor.sessionId
        if (actor.kind != ActorKind.AGENT_SESSION || session == null || session !in gate.sessionIds) {
            throw NotYourGateException()
        }
        val next = when (use) {
            GateUse.RESERVE -> {
                if (gate.status != GateStatus.APPROVED) throw GateNotUsableException(reserveRefusal(gate))
                gate.copy(status = GateStatus.RESERVED, reservedBy = session, reservedAt = clock.now())
            }
            GateUse.CONSUME -> {
                heldBy(gate, session)
                gate.copy(status = GateStatus.CONSUMED, consumedAt = clock.now())
            }
            GateUse.RELEASE -> {
                heldBy(gate, session)
                gate.copy(status = GateStatus.APPROVED, reservedBy = null, reservedAt = null)
            }
        }
        updateGate(next)
        next
    }

    private fun heldBy(gate: Gate, session: Uuid) {
        if (gate.status != GateStatus.RESERVED || gate.reservedBy != session) {
            throw GateNotUsableException("This yes is not reserved by your session: reserve it before the action.")
        }
    }

    private fun reserveRefusal(gate: Gate): String = when (gate.status) {
        GateStatus.RESERVED -> "This yes is already in use by another action. Ask again for this one."
        GateStatus.CONSUMED -> "This yes was already used. Ask again: one yes covers one action."
        GateStatus.PENDING -> "This gate is still waiting for a yes."
        GateStatus.REJECTED -> "This gate was refused."
        else -> "This action is not gated: there is no yes to reserve."
    }
}
