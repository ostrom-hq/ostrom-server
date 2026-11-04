// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.sessions.domain.usecases

import dev.ostrom.server.gates.domain.models.ActorKind
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.domain.models.GateStatus
import dev.ostrom.server.gates.domain.usecases.ListSessionGatesUseCase
import dev.ostrom.server.gates.domain.usecases.SessionGate
import dev.ostrom.server.receipts.domain.usecases.GetReceiptUseCase
import dev.ostrom.server.sessions.domain.models.GateAnswer
import dev.ostrom.server.sessions.domain.models.Session
import dev.ostrom.server.sessions.domain.models.SessionAccess
import dev.ostrom.server.sessions.domain.models.SessionNotFoundException
import dev.ostrom.server.sessions.domain.models.SessionQuery
import dev.ostrom.server.sessions.domain.models.SessionView
import dev.ostrom.server.sessions.domain.repositories.SessionsRepository

/** Reads a session with its gates and receipt. */
interface GetSessionUseCase {
    /**
     * Reads a segment.
     *
     * @param actor The caller: any member of the organization, an agent token only for its own launch.
     * @param id The segment.
     * @return The segment, its gates and its receipt once ended.
     */
    suspend operator fun invoke(actor: GateActor, id: String): SessionView

    /**
     * Adds the gates and the receipt to a segment the caller already holds.
     *
     * @param session The segment.
     * @return Its view.
     */
    suspend fun view(session: Session): SessionView
}

/**
 * [GetSessionUseCase] on the repository, the gates and the receipts.
 *
 * @param repository The sessions.
 * @param sessionGates The gates of a segment.
 * @param receipts The receipts.
 */
class GetSessionUseCaseImpl(
    private val repository: SessionsRepository,
    private val sessionGates: ListSessionGatesUseCase,
    private val receipts: GetReceiptUseCase,
) : GetSessionUseCase {
    override suspend fun invoke(actor: GateActor, id: String): SessionView {
        val session = repository.get(actor.orgId, id)?.takeIf { SessionAccess.canRead(actor, it) }
            ?: throw SessionNotFoundException()
        return view(session)
    }

    override suspend fun view(session: Session): SessionView {
        val gates = sessionGates(session.orgId, session.id)
        return SessionView(
            session = session,
            gates = gates.map { it.id.toString() },
            receipt = session.receiptId?.let { receipts(session.orgId, it) },
            answers = gates.mapNotNull(::answerOf),
        )
    }
}

/** A yes stays a yes once the session reserved it or ran the action. */
private val SAID_YES = setOf(GateStatus.APPROVED, GateStatus.RESERVED, GateStatus.CONSUMED)

/**
 * The answer to [gate], or null while nobody answered it (pending, shadow, ungated): who said the yes or the no that
 * decided it, never another approver's.
 *
 * @param gate A gate of the session.
 * @return Its answer.
 */
internal fun answerOf(gate: SessionGate): GateAnswer? {
    val approved = gate.status in SAID_YES
    val answered = approved || gate.status == GateStatus.REJECTED
    val decision = gate.decisions.lastOrNull { it.approved == approved }?.takeIf { answered }
    return decision?.let {
        GateAnswer(gate.id.toString(), gate.actionClass, approved, it.by, it.at, gate.receiptId)
    }
}

/** Lists the sessions of the organization. */
interface ListSessionsUseCase {
    /**
     * Lists segments, newest first.
     *
     * @param actor The caller: an agent token lists its own launch's only.
     * @param query The filters and the page.
     * @return The segments.
     */
    suspend operator fun invoke(actor: GateActor, query: SessionQuery): List<Session>
}

/**
 * [ListSessionsUseCase] on the repository.
 *
 * @param repository The sessions.
 */
class ListSessionsUseCaseImpl(private val repository: SessionsRepository) : ListSessionsUseCase {
    override suspend fun invoke(actor: GateActor, query: SessionQuery): List<Session> {
        // S2-48: an agent token lists its launch's sessions only, and nothing without a launch.
        if (actor.kind == ActorKind.AGENT_SESSION && actor.launchId == null) return emptyList()
        return repository.list(
            actor.orgId,
            query.copy(launchId = actor.launchId, limit = query.limit.coerceIn(1, SessionQuery.PAGE)),
        )
    }
}
