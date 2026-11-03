// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners.infrastructure

import dev.ostrom.server.runners.domain.Assignment
import dev.ostrom.server.runners.domain.MachineExecutionState
import dev.ostrom.server.runners.domain.MachineReport
import dev.ostrom.server.runners.domain.RunnerAssignmentsRepository
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstantOrNull
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuidOrNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [RunnerAssignmentsRepository] on PostgreSQL (`runners_assignments`, migration 6 of [RunnersMigrations]), a tenant
 * table: every read and write runs confined to the organization ([inTenant], S2-01), so a runner never sees another
 * organization's executions. A runner's last heartbeat is read from `runners_registered`, a tenant table too.
 *
 * @param driver The database.
 */
class RunnerAssignmentsRepositoryImpl(private val driver: Driver) : RunnerAssignmentsRepository {
    override suspend fun assign(orgId: Uuid, sessionId: Uuid, epoch: Long, now: Instant, onlineSince: Instant) {
        driver.inTenant(orgId) {
            execute(
                Statement.create(ASSIGN_SQL).bind("session_id", sessionId).bind("epoch", epoch).bind("org_id", orgId)
                    .bind("now", now).bind("online_since", onlineSince),
            ).getOrThrow()
        }
    }

    override suspend fun find(orgId: Uuid, sessionId: Uuid, epoch: Long): Assignment? = driver.inTenant(orgId) {
        fetchAll(
            Statement.create("$SELECT_SQL WHERE a.org_id = :org_id AND a.session_id = :session_id AND a.epoch = :epoch")
                .bind("org_id", orgId).bind("session_id", sessionId).bind("epoch", epoch),
        ).getOrThrow().firstOrNull()?.toAssignment()
    }

    override suspend fun stop(orgId: Uuid, sessionId: Uuid, now: Instant) {
        driver.inTenant(orgId) {
            execute(
                Statement.create(
                    "UPDATE runners_assignments SET stopped_at = COALESCE(stopped_at, :now) " +
                        "WHERE org_id = :org_id AND session_id = :session_id",
                ).bind("now", now).bind("org_id", orgId).bind("session_id", sessionId),
            ).getOrThrow()
        }
    }

    override suspend fun drop(orgId: Uuid, sessionId: Uuid, now: Instant, onlineSince: Instant): Boolean =
        driver.inTenant(orgId) {
            execute(
                Statement.create(
                    "UPDATE runners_assignments SET stopped_at = COALESCE(stopped_at, :now), " +
                        "dropped_at = COALESCE(dropped_at, :now) WHERE org_id = :org_id AND session_id = :session_id",
                ).bind("now", now).bind("org_id", orgId).bind("session_id", sessionId),
            ).getOrThrow()
            val holders = fetchAll(
                Statement.create(HOLDERS_SQL).bind("org_id", orgId).bind("session_id", sessionId)
                    .bind("online_since", onlineSince),
            ).getOrThrow().first().get("n").asLong()
            if (holders > 0) return@inTenant false
            execute(
                Statement.create("DELETE FROM runners_assignments WHERE org_id = :org_id AND session_id = :session_id")
                    .bind("org_id", orgId).bind("session_id", sessionId),
            ).getOrThrow()
            true
        }

    override suspend fun exchange(orgId: Uuid, runnerId: Uuid, report: MachineReport): List<Assignment> =
        driver.inTenant(orgId) {
            // What it reports; an execution handed over that it no longer has is missing.
            for (held in fetchAll(heldBy(orgId, runnerId)).getOrThrow().map { it.toAssignment() }) {
                val reported = report.executions[held.sessionId to held.epoch]
                val state = reported ?: MachineExecutionState.MISSING.takeIf { held.delivered && !held.stopped }
                execute(
                    Statement.create(
                        "UPDATE runners_assignments SET state = COALESCE(:state, state), holds_workspace = :holds " +
                            "WHERE org_id = :org_id AND session_id = :session_id AND epoch = :epoch",
                    ).bind("state", state?.wire).bind("holds", held.sessionId in report.workspaces)
                        .bind("org_id", orgId).bind("session_id", held.sessionId).bind("epoch", held.epoch),
                ).getOrThrow()
            }
            // One waiting execution at a time, the oldest, so that waiting work spreads over the online runners.
            execute(
                Statement.create(TAKE_SQL).bind("runner_id", runnerId).bind("org_id", orgId),
            ).getOrThrow()
            fetchAll(heldBy(orgId, runnerId)).getOrThrow().map { it.toAssignment() }
        }

    override suspend fun delivered(orgId: Uuid, sessionId: Uuid, epoch: Long, now: Instant) {
        driver.inTenant(orgId) {
            execute(
                Statement.create(
                    "UPDATE runners_assignments SET delivered_at = COALESCE(delivered_at, :now) " +
                        "WHERE org_id = :org_id AND session_id = :session_id AND epoch = :epoch",
                ).bind("now", now).bind("org_id", orgId).bind("session_id", sessionId).bind("epoch", epoch),
            ).getOrThrow()
        }
    }

    private fun heldBy(orgId: Uuid, runnerId: Uuid) =
        Statement.create("$SELECT_SQL WHERE a.org_id = :org_id AND a.runner_id = :runner_id ORDER BY a.created_at")
            .bind("org_id", orgId).bind("runner_id", runnerId)

    private fun ResultSet.Row.toAssignment() = Assignment(
        sessionId = get("session_id").asUuid(),
        epoch = get("epoch").asLong(),
        runnerId = get("runner_id").asUuidOrNull(),
        runnerSeenAt = get("last_seen_at").asInstantOrNull(),
        state = get("state").asStringOrNull()?.let { MachineExecutionState.of(it) },
        delivered = get("delivered_at").asInstantOrNull() != null,
        stopped = get("stopped_at").asInstantOrNull() != null,
        dropped = get("dropped_at").asInstantOrNull() != null,
    )

    /** The statements, shared with the tests. */
    internal companion object {
        /** An assignment with its runner's last heartbeat; a removed runner reads as never seen. */
        const val SELECT_SQL: String =
            "SELECT a.session_id, a.epoch, a.runner_id, a.state, a.delivered_at, a.stopped_at, a.dropped_at, " +
                "r.last_seen_at FROM runners_assignments a LEFT JOIN runners_registered r " +
                "ON r.org_id = a.org_id AND r.id = a.runner_id"

        /** Whether runner `r` holds fewer executions not stopped than the capacity it declared (none: no limit). */
        private const val ROOM_SQL: String =
            "(r.capacity IS NULL OR r.capacity > (SELECT COUNT(*) FROM runners_assignments h " +
                "WHERE h.org_id = r.org_id AND h.runner_id = r.id AND h.stopped_at IS NULL))"

        /**
         * A new execution, for the runner of the session's latest one while it is online and has room, for anyone
         * otherwise.
         */
        const val ASSIGN_SQL: String =
            "INSERT INTO runners_assignments (session_id, epoch, org_id, runner_id, created_at) " +
                "SELECT CAST(:session_id AS UUID), CAST(:epoch AS BIGINT), CAST(:org_id AS UUID), (" +
                "SELECT a.runner_id FROM runners_assignments a JOIN runners_registered r " +
                "ON r.org_id = a.org_id AND r.id = a.runner_id WHERE a.org_id = :org_id " +
                "AND a.session_id = :session_id AND r.last_seen_at >= :online_since AND $ROOM_SQL " +
                "ORDER BY a.epoch DESC LIMIT 1" +
                "), CAST(:now AS TIMESTAMPTZ) ON CONFLICT (session_id, epoch) DO NOTHING"

        /**
         * The oldest waiting execution, taken by one runner only even when two ask at once, and only while the runner
         * has room (its heartbeats come one after another: its count does not race with itself).
         */
        const val TAKE_SQL: String =
            "UPDATE runners_assignments SET runner_id = :runner_id WHERE (session_id, epoch) IN (" +
                "SELECT session_id, epoch FROM runners_assignments WHERE org_id = :org_id AND runner_id IS NULL " +
                "AND stopped_at IS NULL ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED) " +
                "AND EXISTS (SELECT 1 FROM runners_registered r WHERE r.org_id = :org_id AND r.id = :runner_id " +
                "AND $ROOM_SQL)"

        /** The online runners that still hold a session's workspace. */
        const val HOLDERS_SQL: String =
            "SELECT COUNT(*) AS n FROM runners_assignments a JOIN runners_registered r " +
                "ON r.org_id = a.org_id AND r.id = a.runner_id WHERE a.org_id = :org_id " +
                "AND a.session_id = :session_id AND a.holds_workspace AND r.last_seen_at >= :online_since"
    }
}
