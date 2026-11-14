// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstantOrNull
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLongOrNull
import io.github.smyrgeorge.sqlx4k.impl.extensions.asBoolean
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import run.ostrom.server.runners.domain.CloudSession
import run.ostrom.server.runners.domain.CloudTransfer
import run.ostrom.server.runners.domain.CloudTransfersRepository
import run.ostrom.server.runners.domain.SessionChange
import run.ostrom.server.runners.domain.SessionFailure
import run.ostrom.server.runners.domain.TransferCompletion
import run.ostrom.server.runners.domain.TransferDirection
import run.ostrom.server.runners.domain.TransferOpening
import run.ostrom.server.runners.domain.TransferQuery
import run.ostrom.server.runners.domain.TransferState
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [CloudTransfersRepository] on PostgreSQL (`runners_session_transfers`, version 16 of [RunnersMigrations]), confined
 * to the organization like the sessions ([inTenant]). A write that touches a session commits its compare-and-set in
 * the same transaction ([CloudSessionsRepositoryImpl.compareAndSetIn]): either both are written or neither.
 *
 * @param driver The database.
 * @param sessions The sessions, whose compare-and-set a transfer shares.
 */
class CloudTransfersRepositoryImpl(
    private val driver: Driver,
    private val sessions: CloudSessionsRepositoryImpl,
) : CloudTransfersRepository {
    override suspend fun find(orgId: Uuid, id: Uuid): CloudTransfer? = driver.inTenant(orgId) {
        fetchAll(
            Statement.create("""SELECT $COLUMNS FROM runners_session_transfers WHERE id = :id AND org_id = :org_id""")
                .bind("id", id).bind("org_id", orgId),
        ).getOrThrow().firstOrNull()?.toTransfer()
    }

    override suspend fun list(orgId: Uuid, query: TransferQuery): List<CloudTransfer> {
        val side = when (query.direction) {
            TransferDirection.INCOMING -> "to_member_id = :member"
            TransferDirection.OUTGOING -> "from_member_id = :member"
            TransferDirection.BOTH -> "(to_member_id = :member OR from_member_id = :member)"
        }
        val states = if (query.states.isEmpty()) {
            ""
        } else {
            " AND state IN (${query.states.joinToString { "'${it.wire}'" }})"
        }
        val session = if (query.sessionId == null) "" else " AND session_id = :session"
        val cursor = if (query.before == null) {
            ""
        } else {
            """ AND (created_at, id) <
                (SELECT created_at, id FROM runners_session_transfers WHERE id = :before AND org_id = :org_id)"""
        }
        val statement = Statement.create(
            """
            SELECT $COLUMNS FROM runners_session_transfers WHERE org_id = :org_id AND $side$states$session$cursor
            ORDER BY created_at DESC, id DESC LIMIT :limit
            """,
        ).bind("org_id", orgId).bind("member", query.memberId).bind("limit", query.limit)
        if (query.sessionId != null) statement.bind("session", query.sessionId)
        if (query.before != null) statement.bind("before", query.before)
        return driver.inTenant(orgId) { fetchAll(statement).getOrThrow().map { it.toTransfer() } }
    }

    override suspend fun open(transfer: CloudTransfer, now: Instant, session: SessionChange?): TransferOpening =
        driver.inTenant(transfer.orgId) {
            // The session's row serializes every transfer asked for it.
            val locked = fetchAll(
                Statement.create("""SELECT id FROM runners_sessions WHERE id = :id AND org_id = :org_id FOR UPDATE""")
                    .bind("id", transfer.sessionId).bind("org_id", transfer.orgId),
            ).getOrThrow().firstOrNull()
            if (locked == null) return@inTenant TransferOpening.Conflict
            replayOrConflict(transfer, now)?.let { return@inTenant it }
            // An offer past its validity does not hold the session: it is recorded expired here.
            execute(
                Statement.create(
                    """
                    UPDATE runners_session_transfers SET state = 'expired', version = version + 1, updated_at = :now
                    WHERE org_id = :org_id AND session_id = :session AND state = 'offered' AND expires_at <= :now
                    """,
                ).bind("now", now).bind("org_id", transfer.orgId).bind("session", transfer.sessionId),
            ).getOrThrow()
            val open = fetchAll(
                Statement.create(
                    """
                    SELECT 1 AS n FROM runners_session_transfers WHERE org_id = :org_id AND session_id = :session AND
                    state IN ('offered', 'accepted') LIMIT 1
                    """,
                ).bind("org_id", transfer.orgId).bind("session", transfer.sessionId),
            ).getOrThrow().firstOrNull()
            if (open != null) return@inTenant TransferOpening.Pending
            if (session != null && sessions.compareAndSetIn(this, session.next, session.nextReconcileAt) == null) {
                return@inTenant TransferOpening.Conflict
            }
            execute(
                Statement.create("INSERT INTO runners_session_transfers ($COLUMNS) VALUES ($VALUES)")
                    .bindAll(transfer),
            ).getOrThrow()
            TransferOpening.Opened(transfer)
        }

    /** A key used before replays its transfer (same request) or conflicts; one past its retention is forgotten. */
    private suspend fun QueryExecutor.replayOrConflict(transfer: CloudTransfer, now: Instant): TransferOpening? {
        val key = transfer.idempotencyKey ?: return null
        val existing = fetchAll(
            Statement.create(
                """
                SELECT $COLUMNS FROM runners_session_transfers WHERE org_id = :org_id AND initiated_by = :by AND
                idempotency_key = :key
                """,
            ).bind("org_id", transfer.orgId).bind("by", transfer.initiatedBy).bind("key", key),
        ).getOrThrow().firstOrNull()?.toTransfer()
        return when {
            existing == null -> null
            existing.createdAt + CloudTransfer.KEY_RETENTION <= now -> {
                execute(
                    Statement.create("""UPDATE runners_session_transfers SET idempotency_key = NULL WHERE id = :id""")
                        .bind("id", existing.id),
                ).getOrThrow()
                null
            }
            existing.requestDigest == transfer.requestDigest -> TransferOpening.Replayed(existing)
            else -> TransferOpening.KeyConflict
        }
    }

    override suspend fun update(expected: CloudTransfer, next: CloudTransfer, session: SessionChange?): CloudTransfer? =
        attempt(expected.orgId) {
            val stored = writeTransfer(expected, next) ?: throw Lost()
            if (session != null && sessions.compareAndSetIn(this, session.next, session.nextReconcileAt) == null) {
                throw Lost()
            }
            stored
        }

    override suspend fun complete(
        expected: CloudSession,
        next: CloudSession,
        transfer: CloudTransfer,
    ): TransferCompletion {
        val current = find(transfer.orgId, transfer.id)
        val stored = current?.let {
            attempt(transfer.orgId) {
                writeTransfer(it, transfer.copy(version = it.version + 1)) ?: throw Lost()
                val session = sessions.compareAndSetIn(this, next, next.updatedAt) ?: throw Lost()
                // Nothing issued to the former owner survives the change: their terminal tickets go.
                execute(
                    Statement.create(
                        """DELETE FROM runners_terminal_tickets WHERE session_id = :id AND org_id = :org_id""",
                    ).bind("id", expected.id).bind("org_id", expected.orgId),
                ).getOrThrow()
                session
            }
        }
        return if (stored == null) {
            TransferCompletion.Conflict
        } else {
            TransferCompletion.Completed(transfer.copy(version = transfer.version), stored)
        }
    }

    /** Runs [block] in the organization's transaction; [Lost] rolls it back and gives null. */
    private suspend fun <T : Any> attempt(orgId: Uuid, block: suspend QueryExecutor.() -> T): T? = try {
        driver.inTenant(orgId) { block() }
    } catch (_: Lost) {
        null
    }

    private suspend fun QueryExecutor.writeTransfer(expected: CloudTransfer, next: CloudTransfer): CloudTransfer? =
        fetchAll(
            Statement.create(
                """
                UPDATE runners_session_transfers SET state = :state, resume = :resume, version = :version,
                updated_at = :updated_at, expires_at = :expires_at, decided_by = :decided_by, decided_at = :decided_at,
                completed_at = :completed_at, at_epoch = :at_epoch, failure_code = :failure_code,
                failure_message = :failure_message WHERE id = :id AND org_id = :org_id AND version = :expected
                RETURNING $COLUMNS
                """,
            ).bind("state", next.state.wire).bind("resume", next.resume).bind("version", next.version)
                .bind("updated_at", next.updatedAt).bind("expires_at", next.expiresAt)
                .bind("decided_by", next.decidedBy).bind("decided_at", next.decidedAt)
                .bind("completed_at", next.completedAt).bind("at_epoch", next.atEpoch)
                .bind("failure_code", next.failure?.code).bind("failure_message", next.failure?.message)
                .bind("id", next.id).bind("org_id", next.orgId).bind("expected", expected.version),
        ).getOrThrow().firstOrNull()?.toTransfer()

    private fun Statement.bindAll(t: CloudTransfer): Statement = bind("id", t.id).bind("org_id", t.orgId)
        .bind("session_id", t.sessionId).bind("from_member_id", t.fromMemberId).bind("to_member_id", t.toMemberId)
        .bind("initiated_by", t.initiatedBy).bind("state", t.state.wire).bind("resume", t.resume)
        .bind("version", t.version).bind("created_at", t.createdAt).bind("updated_at", t.updatedAt)
        .bind("expires_at", t.expiresAt).bind("decided_by", t.decidedBy).bind("decided_at", t.decidedAt)
        .bind("completed_at", t.completedAt).bind("at_epoch", t.atEpoch).bind("failure_code", t.failure?.code)
        .bind("failure_message", t.failure?.message).bind("idempotency_key", t.idempotencyKey)
        .bind("request_digest", t.requestDigest)

    private fun ResultSet.Row.toTransfer(): CloudTransfer = CloudTransfer(
        id = get("id").asUuid(),
        orgId = get("org_id").asUuid(),
        sessionId = get("session_id").asUuid(),
        fromMemberId = get("from_member_id").asString(),
        toMemberId = get("to_member_id").asString(),
        initiatedBy = get("initiated_by").asString(),
        // A state this version does not know is a failed transfer, never an open one.
        state = TransferState.fromWire(get("state").asString()) ?: TransferState.FAILED,
        resume = get("resume").asBoolean(),
        version = get("version").asInt(),
        createdAt = get("created_at").asInstant(),
        updatedAt = get("updated_at").asInstant(),
        expiresAt = get("expires_at").asInstantOrNull(),
        decidedBy = get("decided_by").asStringOrNull(),
        decidedAt = get("decided_at").asInstantOrNull(),
        completedAt = get("completed_at").asInstantOrNull(),
        atEpoch = get("at_epoch").asLongOrNull(),
        failure = get("failure_code").asStringOrNull()
            ?.let { SessionFailure(it, get("failure_message").asString()) },
        idempotencyKey = get("idempotency_key").asStringOrNull(),
        requestDigest = get("request_digest").asStringOrNull(),
    )

    /** A compare-and-set lost: the transaction rolls back. */
    private class Lost : Exception()

    private companion object {
        const val COLUMNS = """
            id, org_id, session_id, from_member_id, to_member_id, initiated_by, state, resume, version, created_at,
            updated_at, expires_at, decided_by, decided_at, completed_at, at_epoch, failure_code, failure_message,
            idempotency_key, request_digest
        """
        const val VALUES = """
            :id, :org_id, :session_id, :from_member_id, :to_member_id, :initiated_by, :state, :resume, :version,
            :created_at, :updated_at, :expires_at, :decided_by, :decided_at, :completed_at, :at_epoch, :failure_code,
            :failure_message, :idempotency_key, :request_digest
        """
    }
}
