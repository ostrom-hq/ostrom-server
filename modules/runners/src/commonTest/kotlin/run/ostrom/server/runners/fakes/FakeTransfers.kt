// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.fakes

import run.ostrom.server.runners.domain.CloudSession
import run.ostrom.server.runners.domain.CloudTransfer
import run.ostrom.server.runners.domain.CloudTransfersRepository
import run.ostrom.server.runners.domain.MemberTransferPolicy
import run.ostrom.server.runners.domain.SessionChange
import run.ostrom.server.runners.domain.TransferCompletion
import run.ostrom.server.runners.domain.TransferDirection
import run.ostrom.server.runners.domain.TransferOpening
import run.ostrom.server.runners.domain.TransferQuery
import run.ostrom.server.runners.domain.TransferRecipient
import run.ostrom.server.runners.domain.TransferRecipients
import run.ostrom.server.runners.domain.TransferState
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Transfers in memory, with the guards of the SQL: the session's compare-and-set and the open transfer per session. */
class InMemoryCloudTransfersRepository(
    private val sessions: InMemoryCloudSessionsRepository,
) : CloudTransfersRepository {
    val transfers = linkedMapOf<Uuid, CloudTransfer>()

    /** Makes the next writes of a transfer lose their race, as if another writer came first. */
    var loseNextUpdates = 0

    override suspend fun find(orgId: Uuid, id: Uuid): CloudTransfer? = transfers[id]?.takeIf { it.orgId == orgId }

    override suspend fun list(orgId: Uuid, query: TransferQuery): List<CloudTransfer> = transfers.values
        .filter { it.orgId == orgId }
        .filter {
            when (query.direction) {
                TransferDirection.INCOMING -> it.toMemberId == query.memberId
                TransferDirection.OUTGOING -> it.fromMemberId == query.memberId
                TransferDirection.BOTH -> it.toMemberId == query.memberId || it.fromMemberId == query.memberId
            }
        }
        .filter { query.states.isEmpty() || it.state in query.states }
        .filter { query.sessionId == null || it.sessionId == query.sessionId }
        .sortedWith(compareByDescending<CloudTransfer> { it.createdAt }.thenByDescending { it.id.toString() })
        .let { all -> query.before?.let { b -> all.dropWhile { it.id != b }.drop(1) } ?: all }
        .take(query.limit)

    @Suppress("ReturnCount", "CyclomaticComplexMethod") // A fake mirrors the guards of the SQL one by one.
    override suspend fun open(transfer: CloudTransfer, now: Instant, session: SessionChange?): TransferOpening {
        if (sessions.sessions[transfer.sessionId] == null) return TransferOpening.Conflict
        val key = transfer.idempotencyKey
        if (key != null) {
            val existing = transfers.values.firstOrNull {
                it.orgId == transfer.orgId && it.initiatedBy == transfer.initiatedBy && it.idempotencyKey == key
            }
            if (existing != null && existing.createdAt + CloudTransfer.KEY_RETENTION > now) {
                return if (existing.requestDigest == transfer.requestDigest) {
                    TransferOpening.Replayed(existing)
                } else {
                    TransferOpening.KeyConflict
                }
            }
            if (existing != null) transfers[existing.id] = existing.copy(idempotencyKey = null)
        }
        transfers.values.filter { it.sessionId == transfer.sessionId && it.state == TransferState.OFFERED }
            .filter { it.expiresAt != null && it.expiresAt <= now }
            .forEach { transfers[it.id] = it.copy(state = TransferState.EXPIRED, version = it.version + 1) }
        if (transfers.values.any { it.sessionId == transfer.sessionId && it.state.open }) return TransferOpening.Pending
        if (session != null && sessions.compareAndSet(session.next, session.nextReconcileAt) == null) {
            return TransferOpening.Conflict
        }
        transfers[transfer.id] = transfer
        return TransferOpening.Opened(transfer)
    }

    @Suppress("ReturnCount") // A fake mirrors the guards of the SQL one by one.
    override suspend fun update(expected: CloudTransfer, next: CloudTransfer, session: SessionChange?): CloudTransfer? {
        if (loseNextUpdates > 0) {
            loseNextUpdates--
            return null
        }
        val current = transfers[expected.id] ?: return null
        if (current.version != expected.version) return null
        if (session != null && sessions.compareAndSet(session.next, session.nextReconcileAt) == null) return null
        transfers[next.id] = next
        return next
    }

    @Suppress("ReturnCount") // A fake mirrors the guards of the SQL one by one.
    override suspend fun complete(
        expected: CloudSession,
        next: CloudSession,
        transfer: CloudTransfer,
    ): TransferCompletion {
        val current = transfers[transfer.id]?.takeIf { it.version == transfer.version - 1 }
            ?: return TransferCompletion.Conflict
        val stored = sessions.compareAndSet(next, next.updatedAt) ?: return TransferCompletion.Conflict
        transfers[transfer.id] = transfer.copy(version = current.version + 1)
        return TransferCompletion.Completed(transfers.getValue(transfer.id), stored)
    }
}

/** Members known by id, with a login and a role; eligible while present. */
class FakeTransferRecipients(vararg members: TransferRecipient) : TransferRecipients {
    val members = members.toMutableList()

    override suspend fun list(orgId: Uuid, limit: Int): List<TransferRecipient> = members.take(limit)

    override suspend fun find(orgId: Uuid, reference: String): String? =
        members.firstOrNull { it.id == reference || it.login == reference }?.id

    override suspend fun eligible(orgId: Uuid, memberId: String): Boolean = members.any { it.id == memberId }

    override suspend fun administers(orgId: Uuid, memberId: String): Boolean =
        members.any { it.id == memberId && it.administrator }
}

/** A policy the test flips. */
class SwitchedTransferPolicy(var membersOffer: Boolean = true) : MemberTransferPolicy {
    override suspend fun membersMayOffer(): Boolean = membersOffer
}

