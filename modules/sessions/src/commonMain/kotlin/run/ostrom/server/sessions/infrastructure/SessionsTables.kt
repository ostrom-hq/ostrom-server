// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sessions.infrastructure

import run.ostrom.server.kernel.database.InstantColumnType
import run.ostrom.server.kernel.database.instant
import org.jetbrains.exposed.v1.core.ColumnSet
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.Transaction
import kotlin.time.Instant

// The tables of [SessionsMigrations], as Exposed reads and writes them: the migrations create the schema, these objects
// only name its columns. `sessions_sessions` is read and written as the serving role with the organization set
// (`JdbcDatabase.withOrganization`, as the sqlx4k repository), its row-level security policy applying to that role.

/** The segments of the agent sessions: unique by `(org_id, id)` and by `(org_id, root_id, segment)`. */
internal object SessionsSessions : Table("sessions_sessions") {
    val orgId = uuid("org_id")
    val id = text("id")
    val rootId = text("root_id")
    val segment = integer("segment")
    val resumedFrom = text("resumed_from").nullable()
    val previousReceiptId = text("previous_receipt_id").nullable()
    val launchId = text("launch_id").nullable()
    val repo = text("repo").nullable()
    val realRepo = text("real_repo").nullable()
    val cwd = text("cwd").nullable()
    val agent = text("agent")
    val dev = text("dev")
    val harnessVersion = text("harness_version").nullable()
    val packageDigest = text("package_digest").nullable()
    val model = text("model").nullable()

    // Not `source`: a ColumnSet already has one.
    val endSource = text("source").nullable()
    val startedAt = instant("started_at")
    val endedAt = instant("ended_at").nullable()
    val lastSeenAt = instant("last_seen_at").nullable()
    val receiptId = text("receipt_id").nullable()
    val tools = integer("tools").nullable()
    val costUsd = double("cost_usd").nullable()
    val exitCode = integer("exit_code").nullable()
    val seen = text("seen")
    val activity = text("activity").nullable()
    val activityAt = instant("activity_at").nullable()
    val activityObservedAt = instant("activity_observed_at").nullable()
    val labels = text("labels")

    override val primaryKey = PrimaryKey(orgId, id)
}

/**
 * The rows of `sessions_stale(cutoff, limit)` (migration 2), the `ostrom_router` function that lists the stale open
 * segments of every organization, read as a table: `SELECT … FROM sessions_stale(CAST(? AS TIMESTAMPTZ), CAST(? AS
 * INTEGER))`, as the identity module reads `identity_token_by_hash`.
 */
internal object SessionsStale : Table("sessions_stale") {
    val orgId = uuid("org_id")
    val id = text("id")
    val lastSeenAt = instant("last_seen_at")

    /**
     * The function called with its arguments, to select from.
     *
     * @param cutoff The last sign of life before which a segment is stale.
     * @param limit At most this many rows.
     */
    fun of(cutoff: Instant, limit: Int): ColumnSet = Call(cutoff, limit)

    private class Call(private val cutoff: Instant, private val limit: Int) : Table(SessionsStale.tableName) {
        override fun describe(s: Transaction, queryBuilder: QueryBuilder): Unit = queryBuilder {
            append(tableName)
            append("(CAST(")
            registerArgument(InstantColumnType(), cutoff)
            append(" AS TIMESTAMPTZ), CAST(")
            registerArgument(IntegerColumnType(), limit)
            append(" AS INTEGER))")
        }
    }
}
