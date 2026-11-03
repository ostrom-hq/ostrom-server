// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.sessions.domain.usecases

import dev.ostrom.core.receipts.Digests
import dev.ostrom.core.receipts.SubjectPreimages
import dev.ostrom.server.gates.domain.models.GateStatus
import dev.ostrom.server.gates.domain.usecases.ListSessionGatesUseCase
import dev.ostrom.server.gates.domain.usecases.SessionGate
import dev.ostrom.server.receipts.domain.services.ReceiptRule
import dev.ostrom.server.receipts.domain.services.ReceiptRules
import dev.ostrom.server.receipts.domain.services.receiptRulesJson
import dev.ostrom.server.receipts.domain.usecases.SignReceiptUseCase
import dev.ostrom.server.receipts.domain.usecases.isoMillis
import dev.ostrom.server.sessions.domain.models.EndSession
import dev.ostrom.server.sessions.domain.models.Session
import dev.ostrom.server.sessions.domain.models.SessionNotFoundException
import dev.ostrom.server.sessions.domain.repositories.SessionsRepository
import dev.ostrom.server.sessions.domain.repositories.SessionsTransaction
import dev.ostrom.server.sessions.domain.services.SessionCosts
import dev.ostrom.server.sessions.domain.usecases.SessionSegments.Companion.clip
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Ends a segment and signs its receipt, whoever asks: its launcher, or the sweep for a lost one. */
interface CloseSessionUseCase {
    /**
     * Ends a segment. Idempotent: an ended segment keeps its receipt; the hook `SessionEnd` then the launcher's net
     * both end it, and the launcher's exit code is added, outside the receipt.

     *
     * @param orgId The organization.
     * @param id The segment.
     * @param end What the end says.
     * @return The ended segment.
     */
    suspend operator fun invoke(orgId: Uuid, id: String, end: EndSession): Session

    /**
     * Ends a segment the caller already locked, in the caller's transaction.
     *
     * @param tx The caller's transaction.
     * @param session The locked segment, as read under its lock.
     * @param end What the end says.
     * @return The ended segment.
     */
    suspend fun closeLocked(tx: SessionsTransaction, session: Session, end: EndSession): Session
}

/**
 * [CloseSessionUseCase]: the session receipt of the prototype (`bridge/index.ts` endSession). Its subject digest is the
 * hash of the segment (id, repository, person, agent, harness, times, the gates it asked with their status and receipt,
 * the segment it resumes, the exit code); it cites the rules in force and the answers that decided its gates. The
 * receipt id comes from the segment, so a retried end returns the same receipt.
 *
 * @param repository The sessions.
 * @param sessionGates The gates of a segment.
 * @param rules The rules in force.
 * @param sign Signs and stores.
 * @param costs The server's measure of a session's cost (S2-48: never the agent's figure).
 * @param clock The time.
 */
class CloseSessionUseCaseImpl(
    private val repository: SessionsRepository,
    private val sessionGates: ListSessionGatesUseCase,
    private val rules: ReceiptRules,
    private val sign: SignReceiptUseCase,
    private val costs: SessionCosts,
    private val clock: Clock,
) : CloseSessionUseCase {
    // Never a connection held while asking the pool for another: ending a segment under its lock used to read the
    // costs, the gates and the rules and sign the receipt from inside the transaction, so that as many ends at once as
    // the pool has connections waited for each other until the pool gave up (perf pass of the launch, 03/11/2026). The
    // reads come first, the end is written under the lock, the receipt is signed after, and its id written last: an
    // end that fails in between leaves a segment ended without its receipt, which the next end signs.
    override suspend fun invoke(orgId: Uuid, id: String, end: EndSession): Session {
        val seen = repository.get(orgId, id) ?: throw SessionNotFoundException()
        if (seen.receiptId != null) return locked(orgId, id) { merged(it, end) to null }.first
        val endedAt = seen.endedAt ?: now()
        val measured = end.copy(
            costUsd = if (seen.endedAt == null) {
                costs.costOf(orgId, seen.launchId, seen.startedAt, endedAt)
            } else {
                seen.costUsd
            },
        )
        val gates = sessionGates(orgId, id)
        val inForce = rules.rulesFor(orgId, seen.repo)
        val (claimed, told) = locked(orgId, id) { session ->
            when {
                session.receiptId != null -> merged(session, end) to null
                // Another end wrote its time and failed before its receipt: this one signs it, as it was written.
                session.endedAt != null -> merged(session, end).let { it to it.told() }
                else -> endedOf(session, endedAt, measured, end) to measured
            }
        }
        val draft = told?.let { draft(claimed, claimed.endedAt ?: endedAt, it, gates, inForce) } ?: return claimed
        val receiptId = (sign(orgId, draft, receiptIdOf(id)).getValue("id") as JsonPrimitive).content
        return locked(orgId, id) { it.copy(receiptId = it.receiptId ?: receiptId) to null }.first
    }

    // The segment under its lock, changed and written in one short transaction that asks the pool for nothing else.
    private suspend fun locked(
        orgId: Uuid,
        id: String,
        change: (Session) -> Pair<Session, EndSession?>,
    ): Pair<Session, EndSession?> = repository.transaction(orgId) {
        val session = lock(id) ?: throw SessionNotFoundException()
        change(session).also { (next, _) -> if (next != session) update(next) }
    }

    override suspend fun closeLocked(tx: SessionsTransaction, session: Session, end: EndSession): Session {
        val orgId = session.orgId
        val id = session.id
        val ended = if (session.receiptId != null) {
            merged(session, end)
        } else {
            val endedAt = session.endedAt ?: now()
            val measured = end.copy(costUsd = costs.costOf(orgId, session.launchId, session.startedAt, endedAt))
            val draft = draft(session, endedAt, measured, sessionGates(orgId, id), rules.rulesFor(orgId, session.repo))
            val receipt = sign(orgId, draft, receiptIdOf(id))
            endedOf(session, endedAt, measured, end).copy(receiptId = (receipt.getValue("id") as JsonPrimitive).content)
        }
        if (ended != session) tx.update(ended)
        return ended
    }

    private fun now(): Instant = Instant.fromEpochMilliseconds(clock.now().toEpochMilliseconds())

    // An ended segment takes what a later end adds outside its receipt: the model and the exit code it lacked.
    private fun merged(session: Session, end: EndSession): Session =
        session.copy(model = session.model ?: end.model, exitCode = session.exitCode ?: end.exitCode)

    private fun endedOf(session: Session, endedAt: Instant, measured: EndSession, end: EndSession): Session =
        session.copy(
            endedAt = endedAt,
            tools = end.tools,
            source = if (end.source == null) session.source else end.source.clip(SessionSegments.TEXT),
            model = session.model ?: end.model,
            costUsd = measured.costUsd,
            exitCode = end.exitCode,
        )

    // What a written end tells its receipt, when the end that wrote it is gone.
    private fun Session.told(): EndSession = EndSession(model = model, costUsd = costUsd, exitCode = exitCode)

    private fun receiptIdOf(id: String): String = "rcpt_session_$id"

    /** The receipt drafts. */
    companion object {
        /**
         * The receipt of an ended segment, before the receipts module adds its id, date and signature.
         *
         * @param session The segment.
         * @param endedAt When it ends.
         * @param end What the end says.
         * @param gates Its gates.
         * @param rules The rules in force.
         * @return The draft.
         */
        fun draft(
            session: Session,
            endedAt: Instant,
            end: EndSession,
            gates: List<SessionGate>,
            rules: List<ReceiptRule>,
        ): JsonObject = buildJsonObject {
            put(
                "subject",
                buildJsonObject {
                    put("kind", "session")
                    put("id", session.id)
                    put("digest", Digests.digestOf(preimage(session, endedAt, end.exitCode, gates)))
                },
            )
            put("initiator", session.dev)
            put("agent", session.agent)
            (end.model ?: session.model)?.let { put("model", it) }
            end.costUsd?.let { put("costUsd", it) }
            put("rules", receiptRulesJson(rules))
            put("decisions", JsonArray(gates.flatMap(::decisionsOf)))
        }

        /**
         * The prototype's session preimage, field for field.
         *
         * @param session The segment.
         * @param endedAt When it ends.
         * @param exitCode The launcher's exit code.
         * @param gates Its gates.
         * @return The object whose digest is the receipt's subject digest.
         */
        fun preimage(session: Session, endedAt: Instant, exitCode: Int?, gates: List<SessionGate>): JsonObject =
            SubjectPreimages.session(
                id = session.id,
                repo = session.repo,
                dev = session.dev,
                agent = session.agent,
                harness = session.harnessVersion,
                startedAt = session.startedAt.isoMillis(),
                endedAt = endedAt.isoMillis(),
                gates = gates.map {
                    SubjectPreimages.SessionGate(
                        it.id.toString(),
                        it.actionClass,
                        it.mode.name.lowercase(),
                        statusOf(it.status),
                        it.receiptId,
                    )
                },
                resume = session.resumedFrom?.let {
                    SubjectPreimages.Resume(it, session.previousReceiptId, session.segment)
                },
                exitCode = exitCode,
            )

        /**
         * A gate status as the contract writes it: a held yes reads `approved`.
         *
         * @param status The status.
         * @return Its name.
         */
        fun statusOf(status: GateStatus): String =
            if (status == GateStatus.RESERVED) "approved" else status.name.lowercase()

        private fun decisionsOf(gate: SessionGate): List<JsonObject> = gate.decisions.map {
            buildJsonObject {
                put("approvalId", it.approvalId.toString())
                put("by", it.by)
                put("at", it.at.isoMillis())
                put("result", if (it.approved) "approved" else "rejected")
                if (it.selfApproved) put("selfApproved", true)
            }
        }
    }
}
