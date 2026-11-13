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
import run.ostrom.server.gateway.domain.Workload
import run.ostrom.server.secrets.infrastructure.executeInOrg
import run.ostrom.server.secrets.infrastructure.fetchInOrg
import run.ostrom.server.secrets.infrastructure.inTenant
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

/**
 * [AdmissionQueueRepository] on PostgreSQL ([GatewayMigrations] version 3): one row per call from its arrival to its
 * end (`running` once admitted), and each member's last turn per scope. Every entry runs under an advisory lock of the
 * org, in one short transaction: two replicas never admit past a member's limit or out of turn together, and no
 * connection is held between two polls. Times and expiry are the database's clock.
 *
 * @param driver The database.
 */
class AdmissionQueueRepositoryImpl(private val driver: Driver) : AdmissionQueueRepository {
    override suspend fun enter(request: AdmissionRequest, first: Boolean): AdmissionEntry =
        driver.inTenant(request.orgId) { decide(request, first) }

    private suspend fun Transaction.decide(request: AdmissionRequest, first: Boolean): AdmissionEntry {
        fun sql(text: String) = Statement.create(text).bind("org_id", request.orgId)
        fetchAll(Statement.create(ADMISSION_LOCK_SQL).bind("key", "gateway_admissions:${request.orgId}")).getOrThrow()
        execute(sql(ADMISSION_PURGE_SQL)).getOrThrow()
        val waited = if (first) {
            0L
        } else {
            fetchAll(sql(ADMISSION_WAITED_SQL).bind("id", request.id)).getOrThrow().firstOrNull()
                ?.let { it.get("waited_ms").asLong() } ?: throw QueueRefusedException(RefusalKind.QUEUE_EXPIRED)
        }
        val running = request.limit?.let {
            fetchAll(sql(ADMISSION_RUNNING_SQL).bind("member_id", request.memberId)).getOrThrow().first()
                .get("running").asLong().toInt()
        } ?: 0
        val others = fetchAll(sql(ADMISSION_CANDIDATES_SQL).bind("scope", request.scope).bind("id", request.id))
            .getOrThrow().map(::candidate)
        val turns = fetchAll(sql(ADMISSION_TURNS_SQL).bind("scope", request.scope).bind("member_id", request.memberId))
            .getOrThrow().first()
        val me = QueueCandidate(
            request.id,
            request.memberId,
            request.workload,
            waited.milliseconds,
            turns.get("mine").asLong(),
        )
        val entry = QueueTurns.decide(me, others, running, request.limit)
        when (entry) {
            AdmissionEntry.Admitted -> admit(request, first, turns.get("mine").asLong(), turns.get("top").asLong() + 1)
            is AdmissionEntry.Waiting -> wait(request, first, entry.blocked)
        }
        return entry
    }

    private suspend fun Transaction.admit(request: AdmissionRequest, first: Boolean, before: Long, turn: Long) {
        val write = if (first) request.inserted(ADMISSION_RUN_SQL) else Statement.create(ADMISSION_START_SQL)
        execute(write.bind("org_id", request.orgId).bind("id", request.id).bind("turn", turn).bind("before", before))
            .getOrThrow()
        execute(
            Statement.create(ADMISSION_TURN_SQL).bind("org_id", request.orgId).bind("scope", request.scope)
                .bind("member_id", request.memberId).bind("turn", turn),
        ).getOrThrow()
    }

    private suspend fun Transaction.wait(request: AdmissionRequest, first: Boolean, blocked: Boolean) {
        // A call blocked by its own member's limit leaves the next turn to the others until its member has room.
        val readyIn = if (blocked) AdmissionQueueRepository.WAITING_LEASE.inWholeSeconds else 0
        if (first) {
            val bounds = Statement.create(ADMISSION_WAITING_SQL).bind("org_id", request.orgId)
                .bind("member_id", request.memberId)
            val row = fetchAll(bounds).getOrThrow().first()
            val memberFull = request.limit != null &&
                row.get("member_waiting").asLong() >= AdmissionQueueRepository.MEMBER_BOUND
            if (memberFull || row.get("org_waiting").asLong() >= AdmissionQueueRepository.ORG_BOUND) {
                throw QueueRefusedException(RefusalKind.QUEUE_FULL)
            }
        }
        val write = if (first) request.inserted(ADMISSION_WAIT_SQL) else Statement.create(ADMISSION_POLL_SQL)
        execute(write.bind("org_id", request.orgId).bind("id", request.id).bind("ready_in", readyIn)).getOrThrow()
    }

    override suspend fun requeue(orgId: Uuid, id: Uuid) = driver.inTenant(orgId) {
        // The turn it took goes back to its member, unless another of their calls took a later one since.
        execute(Statement.create(ADMISSION_GIVE_BACK_SQL).bind("org_id", orgId).bind("id", id)).getOrThrow()
        execute(Statement.create(ADMISSION_REQUEUE_SQL).bind("org_id", orgId).bind("id", id)).getOrThrow()
        Unit
    }

    override suspend fun renew(orgId: Uuid, id: Uuid) {
        driver.executeInOrg(orgId, Statement.create(ADMISSION_RENEW_SQL).bind("org_id", orgId).bind("id", id))
            .getOrThrow()
    }

    override suspend fun release(orgId: Uuid, id: Uuid) {
        driver.executeInOrg(orgId, Statement.create(ADMISSION_RELEASE_SQL).bind("org_id", orgId).bind("id", id))
            .getOrThrow()
    }

    override suspend fun counts(orgId: Uuid, memberId: Uuid): QueueCounts {
        val row = driver.fetchInOrg(
            orgId,
            Statement.create(ADMISSION_COUNTS_SQL).bind("org_id", orgId).bind("member_id", memberId),
        ).getOrThrow().first()
        return QueueCounts(row.get("waiting").asLong().toInt(), row.get("mine").asLong().toInt())
    }

    private fun AdmissionRequest.inserted(sql: String): Statement = Statement.create(sql)
        .bind("member_id", memberId).bind("scope", scope).bind("workload", workload.name)

    private fun candidate(row: ResultSet.Row) = QueueCandidate(
        id = row.get("id").asUuid(),
        memberId = row.get("member_id").asUuid(),
        workload = Workload.valueOf(row.get("workload").asString()),
        waited = row.get("waited_ms").asLong().milliseconds,
        lastTurn = row.get("last_turn").asLong(),
    )
}

private val WAITING_SECONDS = AdmissionQueueRepository.WAITING_LEASE.inWholeSeconds
private val RUNNING_SECONDS = AdmissionQueueRepository.RUNNING_LEASE.inWholeSeconds
private val ALIVE_SECONDS = AdmissionQueueRepository.ALIVE.inWholeSeconds
private const val WAITED = "CAST(EXTRACT(EPOCH FROM (now() - created_at)) * 1000 AS BIGINT)"

/** The org's lock: its entries are decided one at a time, on every replica. */
internal const val ADMISSION_LOCK_SQL =
    "SELECT 1 AS locked FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))) AS l"

/** The org's expired entries: waits no longer polled, and calls whose replica died. */
internal const val ADMISSION_PURGE_SQL = "DELETE FROM gateway_admissions WHERE org_id = :org_id AND expires_at <= now()"

/** How long a waiting call has waited; no row when its place expired. */
internal const val ADMISSION_WAITED_SQL =
    "SELECT $WAITED AS waited_ms FROM gateway_admissions WHERE org_id = :org_id AND id = :id"

/** The calls a member runs now. */
internal const val ADMISSION_RUNNING_SQL =
    "SELECT count(*) AS running FROM gateway_admissions WHERE org_id = :org_id AND member_id = :member_id AND running"

/**
 * The other calls of the scope that wait and may run: not blocked by their member's limit, and polled lately (a call
 * whose replica stopped never holds the turn for long).
 */
internal val ADMISSION_CANDIDATES_SQL =
    "SELECT a.id, a.member_id, a.workload, CAST(EXTRACT(EPOCH FROM (now() - a.created_at)) * 1000 AS BIGINT) " +
        "AS waited_ms, COALESCE(t.turn, 0) AS last_turn FROM gateway_admissions a " +
        "LEFT JOIN gateway_admission_turns t ON t.org_id = a.org_id AND t.scope = a.scope " +
        "AND t.member_id = a.member_id " +
        "WHERE a.org_id = :org_id AND a.scope = :scope AND NOT a.running AND a.ready_at <= now() " +
        "AND a.polled_at > now() - INTERVAL '$ALIVE_SECONDS seconds' AND a.id <> :id " +
        "ORDER BY a.created_at LIMIT ${AdmissionQueueRepository.ORG_BOUND}"

/** The member's last turn in the scope, and the scope's last. */
internal const val ADMISSION_TURNS_SQL =
    "SELECT COALESCE(MAX(turn) FILTER (WHERE member_id = :member_id), 0) AS mine, COALESCE(MAX(turn), 0) AS top " +
        "FROM gateway_admission_turns WHERE org_id = :org_id AND scope = :scope"

/** A call admitted at once. */
internal val ADMISSION_RUN_SQL =
    "INSERT INTO gateway_admissions (id, org_id, member_id, scope, workload, running, created_at, ready_at, " +
        "polled_at, expires_at, turn_taken, turn_before) VALUES (:id, :org_id, :member_id, :scope, :workload, TRUE, " +
        "now(), now(), now(), now() + INTERVAL '$RUNNING_SECONDS seconds', :turn, :before)"

/** A waiting call admitted. */
internal val ADMISSION_START_SQL =
    "UPDATE gateway_admissions SET running = TRUE, expires_at = now() + INTERVAL '$RUNNING_SECONDS seconds', " +
        "turn_taken = :turn, turn_before = :before WHERE org_id = :org_id AND id = :id"

/** The member's turn taken: the scope's last. */
internal const val ADMISSION_TURN_SQL =
    "INSERT INTO gateway_admission_turns (org_id, scope, member_id, turn) " +
        "VALUES (:org_id, :scope, :member_id, :turn) " +
        "ON CONFLICT (org_id, scope, member_id) DO UPDATE SET turn = EXCLUDED.turn"

/** The calls waiting in the org, and the member's. */
internal const val ADMISSION_WAITING_SQL =
    "SELECT count(*) AS org_waiting, count(*) FILTER (WHERE member_id = :member_id) AS member_waiting " +
        "FROM gateway_admissions WHERE org_id = :org_id AND NOT running"

/** A call that starts waiting. */
internal val ADMISSION_WAIT_SQL =
    "INSERT INTO gateway_admissions (id, org_id, member_id, scope, workload, running, created_at, ready_at, " +
        "polled_at, expires_at) VALUES (:id, :org_id, :member_id, :scope, :workload, FALSE, now(), " +
        "now() + make_interval(secs => :ready_in), now(), now() + INTERVAL '$WAITING_SECONDS seconds')"

/** A waiting call that polled. */
internal val ADMISSION_POLL_SQL =
    "UPDATE gateway_admissions SET ready_at = now() + make_interval(secs => :ready_in), polled_at = now(), " +
        "expires_at = now() + INTERVAL '$WAITING_SECONDS seconds' WHERE org_id = :org_id AND id = :id"

/** The turn a call took, given back to its member as it goes back to wait: it was not served. */
internal const val ADMISSION_GIVE_BACK_SQL =
    "UPDATE gateway_admission_turns t SET turn = a.turn_before FROM gateway_admissions a " +
        "WHERE a.org_id = :org_id AND a.id = :id AND a.running AND t.org_id = a.org_id AND t.scope = a.scope " +
        "AND t.member_id = a.member_id AND t.turn = a.turn_taken"

/** A running call back to wait, at its place. */
internal val ADMISSION_REQUEUE_SQL =
    "UPDATE gateway_admissions SET running = FALSE, turn_taken = NULL, turn_before = NULL, ready_at = now(), " +
        "polled_at = now(), " +
        "expires_at = now() + INTERVAL '$WAITING_SECONDS seconds' WHERE org_id = :org_id AND id = :id"

/** A running call's lease extended. */
internal val ADMISSION_RENEW_SQL =
    "UPDATE gateway_admissions SET expires_at = now() + INTERVAL '$RUNNING_SECONDS seconds' " +
        "WHERE org_id = :org_id AND id = :id AND running"

/** A call that ended or stopped waiting. */
internal const val ADMISSION_RELEASE_SQL = "DELETE FROM gateway_admissions WHERE org_id = :org_id AND id = :id"

/** The calls waiting now, the org's and the member's. */
internal const val ADMISSION_COUNTS_SQL =
    "SELECT count(*) AS waiting, count(*) FILTER (WHERE member_id = :member_id) AS mine FROM gateway_admissions " +
        "WHERE org_id = :org_id AND NOT running AND expires_at > now()"
