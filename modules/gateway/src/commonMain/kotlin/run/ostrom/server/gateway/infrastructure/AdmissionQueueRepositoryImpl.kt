// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway.infrastructure

import run.ostrom.server.gateway.domain.AdmissionEntry
import run.ostrom.server.gateway.domain.AdmissionQueueRepository
import run.ostrom.server.gateway.domain.AdmissionRequest
import run.ostrom.server.gateway.domain.QueueCandidate
import run.ostrom.server.gateway.domain.QueueCounts
import run.ostrom.server.gateway.domain.QueueRefusedException
import run.ostrom.server.gateway.domain.QueueTurns
import run.ostrom.server.gateway.domain.RefusalKind
import run.ostrom.server.kernel.database.JdbcDatabase
import org.jetbrains.exposed.v1.core.statements.Statement
import org.jetbrains.exposed.v1.core.statements.StatementBuilder
import org.jetbrains.exposed.v1.core.statements.buildStatement
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.statements.toExecutable
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

/**
 * [AdmissionQueueRepository] on PostgreSQL through Exposed ([GatewayAdmissions], [GatewayAdmissionTurns]; the reads in
 * `AdmissionReads.kt`, the writes in `AdmissionArrivals.kt` and `AdmissionLeases.kt`): one row per call from its
 * arrival to its end (`running` once admitted), and each member's last turn per scope. Every entry runs under an
 * advisory lock of the org, in one short transaction confined to the org ([JdbcDatabase.inOrg]): two replicas never
 * admit past a member's limit or out of turn together, and no connection is held between two polls. Times and expiry
 * are the database's clock.
 *
 * @param database The JDBC pool.
 */
class AdmissionQueueRepositoryImpl(private val database: JdbcDatabase) : AdmissionQueueRepository {
    override suspend fun enter(request: AdmissionRequest, first: Boolean): AdmissionEntry =
        database.inOrg(request.orgId) { decide(request, first) }

    private fun JdbcTransaction.decide(request: AdmissionRequest, first: Boolean): AdmissionEntry {
        val orgId = request.orgId
        advisoryLock(admissionLockKey(orgId)).toList()
        write { admissionPurge(orgId) }
        val waited = if (first) {
            bound(request)
            0L
        } else {
            admissionWaited(orgId, request.id).singleOrNull()
                ?: throw QueueRefusedException(RefusalKind.QUEUE_EXPIRED)
        }
        val running = if (request.limit == null) 0 else admissionRunning(orgId, request.memberId).single()
        val others = admissionCandidates(orgId, request.scope, request.id).rows()
        val turns = admissionTurns(orgId, request.scope, request.memberId).single()
        val me = QueueCandidate(request.id, request.memberId, request.workload, waited.milliseconds, turns.mine)
        val entry = QueueTurns.decide(me, others, running, request.limit, request.limitedMembers)
        when (entry) {
            AdmissionEntry.Admitted -> {
                val turn = turns.top + 1
                if (first) {
                    write { admissionRun(request, turn, turns.mine) }
                } else {
                    write { admissionStart(request, turn, turns.mine) }
                }
                write { admissionTurn(request, turn) }
            }
            is AdmissionEntry.Waiting -> {
                // A call blocked by its own member's limit leaves the next turn to the others until its member has
                // room.
                val readyIn = if (entry.blocked) AdmissionQueueRepository.WAITING_LEASE.inWholeSeconds else 0
                if (first) {
                    write { admissionWait(request, readyIn) }
                } else {
                    write { admissionPoll(orgId, request.id, readyIn) }
                }
            }
        }
        return entry
    }

    /**
     * The bounds, as a call arrives: checked whether it waits or not, since a call admitted without a turn (no limit
     * among the waiting calls) goes back to wait when the accounts are busy, as TeamCode bounds every arrival.
     */
    private fun bound(request: AdmissionRequest) {
        val waiting = admissionWaiting(request.orgId, request.memberId).single()
        val memberFull = request.limit != null && waiting.member >= AdmissionQueueRepository.MEMBER_BOUND
        if (memberFull || waiting.org >= AdmissionQueueRepository.ORG_BOUND) {
            throw QueueRefusedException(RefusalKind.QUEUE_FULL)
        }
    }

    override suspend fun requeue(orgId: Uuid, id: Uuid) = database.inOrg(orgId) {
        // The turn it took goes back to its member, unless another of their calls took a later one since.
        write { admissionGiveBack(orgId, id) }
        write { admissionRequeue(orgId, id) }
        Unit
    }

    override suspend fun renew(orgId: Uuid, id: Uuid) {
        database.inOrg(orgId) { write { admissionRenew(orgId, id) } }
    }

    override suspend fun release(orgId: Uuid, id: Uuid) {
        database.inOrg(orgId) { write { admissionRelease(orgId, id) } }
    }

    override suspend fun counts(orgId: Uuid, memberId: Uuid): QueueCounts = database.inOrg(orgId) {
        admissionCounts(orgId, memberId).single()
    }
}

/** The advisory lock key of an org's admissions: its entries are decided one at a time, on every replica. */
internal fun admissionLockKey(orgId: Uuid): String = "gateway_admissions:$orgId"

/**
 * Runs one write built by [body] in this transaction.
 *
 * @return What the statement returns (rows written).
 */
internal fun <T : Any> JdbcTransaction.write(body: StatementBuilder.() -> Statement<T>): T? =
    buildStatement(body).toExecutable().execute(this)

