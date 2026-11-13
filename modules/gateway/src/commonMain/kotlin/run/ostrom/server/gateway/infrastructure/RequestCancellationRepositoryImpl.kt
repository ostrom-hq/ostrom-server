// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway.infrastructure

import run.ostrom.server.gateway.domain.CancellationLimitException
import run.ostrom.server.gateway.domain.RequestCancellationRepository
import run.ostrom.server.secrets.infrastructure.fetchInOrg
import run.ostrom.server.secrets.infrastructure.inTenant
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import kotlin.uuid.Uuid

/**
 * [RequestCancellationRepository] on PostgreSQL ([GatewayMigrations] version 4). A marker is written under an advisory
 * lock of the org, so that two replicas never pass the bounds together; a read is one lookup by primary key.
 *
 * @param driver The database.
 */
class RequestCancellationRepositoryImpl(private val driver: Driver) : RequestCancellationRepository {
    override suspend fun cancel(orgId: Uuid, memberId: Uuid, requestId: Uuid) = driver.inTenant(orgId) {
        fun sql(text: String) = Statement.create(text).bind("org_id", orgId)
        fetchAll(Statement.create(CANCELLATION_LOCK_SQL).bind("key", "gateway_cancellations:$orgId")).getOrThrow()
        execute(sql(CANCELLATION_PURGE_SQL)).getOrThrow()
        val row = fetchAll(sql(CANCELLATION_COUNTS_SQL).bind("member_id", memberId).bind("request_id", requestId))
            .getOrThrow().first()
        // Idempotent: a marker already there is neither extended nor counted again.
        if (row.get("present").asLong() == 0L) {
            if (row.get("member_markers").asLong() >= RequestCancellationRepository.MEMBER_LIMIT ||
                row.get("org_markers").asLong() >= RequestCancellationRepository.ORG_LIMIT
            ) {
                throw CancellationLimitException()
            }
            execute(sql(CANCELLATION_INSERT_SQL).bind("member_id", memberId).bind("request_id", requestId))
                .getOrThrow()
        }
    }

    override suspend fun isCancelled(orgId: Uuid, memberId: Uuid, requestId: Uuid): Boolean {
        val statement = Statement.create(CANCELLATION_READ_SQL).bind("org_id", orgId).bind("member_id", memberId)
            .bind("request_id", requestId)
        return driver.fetchInOrg(orgId, statement).getOrThrow().any()
    }
}

private val RETENTION_SECONDS = RequestCancellationRepository.RETENTION.inWholeSeconds

/** The org's lock: its markers are counted and written one at a time, on every replica. */
internal const val CANCELLATION_LOCK_SQL =
    "SELECT 1 AS locked FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))) AS l"

/** The org's expired markers. */
internal const val CANCELLATION_PURGE_SQL =
    "DELETE FROM gateway_cancellations WHERE org_id = :org_id AND expires_at <= now()"

/** Whether the marker is there, and the member's and the org's markers. */
internal const val CANCELLATION_COUNTS_SQL =
    "SELECT count(*) FILTER (WHERE member_id = :member_id AND request_id = :request_id) AS present, " +
        "count(*) FILTER (WHERE member_id = :member_id) AS member_markers, count(*) AS org_markers " +
        "FROM gateway_cancellations WHERE org_id = :org_id"

/** A new marker. */
internal val CANCELLATION_INSERT_SQL =
    "INSERT INTO gateway_cancellations (org_id, member_id, request_id, expires_at) " +
        "VALUES (:org_id, :member_id, :request_id, now() + INTERVAL '$RETENTION_SECONDS seconds')"

/** The member's live marker of a call. */
internal const val CANCELLATION_READ_SQL =
    "SELECT 1 AS cancelled FROM gateway_cancellations WHERE org_id = :org_id AND member_id = :member_id " +
        "AND request_id = :request_id AND expires_at > now()"
