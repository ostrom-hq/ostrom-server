// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.runners.domain.TerminalHosts
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import kotlin.uuid.Uuid

/**
 * [TerminalHosts] on PostgreSQL (`runners_terminal_hosts`, [RunnersMigrations] version 11), under the organization's
 * row-level security, timed by the database's clock: a hold names [replicaUrl], and only a newer or the same execution
 * takes a session over from another replica.
 *
 * @param driver The database.
 * @param replicaUrl This replica's URL, as the others reach it.
 */
class TerminalHostsImpl(private val driver: Driver, private val replicaUrl: String) : TerminalHosts {
    override suspend fun hold(orgId: Uuid, sessionId: Uuid, epoch: Long) = driver.inTenant(orgId) {
        // The organization's holds past their lease (their replica died) are dropped as new ones are taken.
        execute(Statement.create(TERMINAL_HOST_PURGE_SQL).bind("org_id", orgId)).getOrThrow()
        execute(held(TERMINAL_HOST_HOLD_SQL, orgId, sessionId, epoch)).getOrThrow()
        Unit
    }

    override suspend fun renew(orgId: Uuid, sessionId: Uuid, epoch: Long) = driver.inTenant(orgId) {
        execute(held(TERMINAL_HOST_RENEW_SQL, orgId, sessionId, epoch)).getOrThrow()
        Unit
    }

    override suspend fun release(orgId: Uuid, sessionId: Uuid, epoch: Long) = driver.inTenant(orgId) {
        execute(held(TERMINAL_HOST_RELEASE_SQL, orgId, sessionId, epoch)).getOrThrow()
        Unit
    }

    override suspend fun host(orgId: Uuid, sessionId: Uuid): String? = driver.inTenant(orgId) {
        val statement = Statement.create(TERMINAL_HOST_FIND_SQL).bind("org_id", orgId).bind("session_id", sessionId)
        fetchAll(statement).getOrThrow().firstOrNull()?.let { it.get("replica_url").asString() }
    }

    private fun held(sql: String, orgId: Uuid, sessionId: Uuid, epoch: Long) = Statement.create(sql)
        .bind("org_id", orgId).bind("session_id", sessionId).bind("epoch", epoch).bind("url", replicaUrl)
}

private val LEASE_SECONDS = TerminalHosts.LEASE.inWholeSeconds

/** This replica holds the session, unless a newer execution is held elsewhere (the expired are purged first). */
internal val TERMINAL_HOST_HOLD_SQL =
    "INSERT INTO runners_terminal_hosts (session_id, org_id, epoch, replica_url, expires_at) " +
        "VALUES (:session_id, :org_id, :epoch, :url, now() + INTERVAL '$LEASE_SECONDS seconds') " +
        "ON CONFLICT (session_id) DO UPDATE SET epoch = EXCLUDED.epoch, replica_url = EXCLUDED.replica_url, " +
        "expires_at = EXCLUDED.expires_at " +
        "WHERE runners_terminal_hosts.epoch <= EXCLUDED.epoch"

/** The organization's holds past their lease. */
internal const val TERMINAL_HOST_PURGE_SQL =
    "DELETE FROM runners_terminal_hosts WHERE org_id = :org_id AND expires_at <= now()"

/**
 * This replica's hold, extended, or written again when it was lost (a failed write, a purge after a long outage); never
 * over another replica's live hold of the same or a newer execution.
 */
internal val TERMINAL_HOST_RENEW_SQL =
    "INSERT INTO runners_terminal_hosts (session_id, org_id, epoch, replica_url, expires_at) " +
        "VALUES (:session_id, :org_id, :epoch, :url, now() + INTERVAL '$LEASE_SECONDS seconds') " +
        "ON CONFLICT (session_id) DO UPDATE SET epoch = EXCLUDED.epoch, replica_url = EXCLUDED.replica_url, " +
        "expires_at = EXCLUDED.expires_at " +
        "WHERE runners_terminal_hosts.epoch < EXCLUDED.epoch OR runners_terminal_hosts.expires_at <= now() " +
        "OR (runners_terminal_hosts.epoch = EXCLUDED.epoch " +
        "AND runners_terminal_hosts.replica_url = EXCLUDED.replica_url)"

/** This replica's hold, given back. */
internal const val TERMINAL_HOST_RELEASE_SQL =
    "DELETE FROM runners_terminal_hosts " +
        "WHERE org_id = :org_id AND session_id = :session_id AND epoch = :epoch AND replica_url = :url"

/** The session's live host. */
internal const val TERMINAL_HOST_FIND_SQL =
    "SELECT replica_url FROM runners_terminal_hosts " +
        "WHERE org_id = :org_id AND session_id = :session_id AND expires_at > now()"
