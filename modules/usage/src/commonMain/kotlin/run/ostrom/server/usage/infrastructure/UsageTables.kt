// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.usage.infrastructure

import run.ostrom.server.kernel.database.instant
import java.time.LocalDate
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.Table

/**
 * The tables of [UsageMigrations], as Exposed reads and writes them. The migrations create the schema; these objects
 * only name its columns and their types, and never create anything (no `SchemaUtils`). Every one is a tenant table:
 * read and written in `JdbcDatabase.inOrg`, each query filtered on `org_id` too. The daily totals (`usage_daily`)
 * are written by the ledger's triggers alone and read by the reports module: no object here.
 */
internal object UsageEvents : Table("usage_events") {
    val id = uuid("id")
    val orgId = uuid("org_id")
    val memberId = uuid("member_id").nullable()

    /** A [run.ostrom.server.usage.domain.UsageKind] name. */
    val kind = text("kind")
    val name = text("name")
    val provider = text("provider").nullable()
    val accountId = uuid("account_id").nullable()
    val sessionId = text("session_id").nullable()

    /** The launch whose agent token sent the event (version 3). */
    val launchId = text("launch_id").nullable()

    /** The workflow the launch plays (version 8). */
    val workflowId = text("workflow_id").nullable()

    /** The named pool of the shared account that served the call, when it was served (version 14). */
    val poolId = text("pool_id").nullable()
    val inputTokens = long("input_tokens")
    val outputTokens = long("output_tokens")
    val cacheReadTokens = long("cache_read_tokens")
    val cacheWriteTokens = long("cache_write_tokens")
    val costMicros = long("cost_micros")
    val durationMs = long("duration_ms")
    val outcome = text("outcome")
    val at = instant("at")

    override val primaryKey = PrimaryKey(id)
}

/** What the model calls in flight may still cost (version 5), until their call is recorded or the hold retired. */
internal object UsageHolds : Table("usage_holds") {
    val callId = uuid("call_id")
    val orgId = uuid("org_id")
    val memberId = uuid("member_id")
    val workflowId = text("workflow_id").nullable()

    /** The launch whose agent token made the call (version 12): its hold counts against that launch's budget. */
    val launchId = text("launch_id").nullable()
    val micros = long("micros")
    val expiresAt = instant("expires_at")

    /** When the call first asked (version 9): an expired hold stays charged at that time. */
    val heldAt = instant("held_at")

    override val primaryKey = PrimaryKey(callId)
}

/**
 * The final corrections of unknown charges (version 13, TeamCode's `usage_reconciliations`): one a hold at most, with
 * the attribution the call had when it was held. Kept as long as their holds (`HoldWindow.RETENTION`).
 */
internal object UsageReconciliations : Table("usage_reconciliations") {
    val callId = uuid("call_id")
    val orgId = uuid("org_id")
    val memberId = uuid("member_id")
    val workflowId = text("workflow_id").nullable()
    val launchId = text("launch_id").nullable()
    val providerRequestId = text("provider_request_id").nullable()
    val heldAt = instant("held_at")
    val previousMicros = long("previous_micros")
    val micros = long("micros")

    /** A [run.ostrom.server.usage.domain.UsageEvidence] name. */
    val evidence = text("source")
    val reference = text("reference")
    val reason = text("reason")
    val reconciledBy = uuid("reconciled_by")
    val reconciledAt = instant("reconciled_at")

    override val primaryKey = PrimaryKey(callId)
}

/** The spend of each UTC day by member and workflow (version 10), written by the ledger's triggers only. */
internal object UsageSpendDays : Table("usage_spend_days") {
    val orgId = uuid("org_id")
    val day = registerColumn("day", DateColumnType())
    val memberId = uuid("member_id").nullable()
    val workflowId = text("workflow_id").nullable()
    val costMicros = long("cost_micros")
}

/** The events a member recorded in a minute of the database's clock (version 11). */
internal object UsageIngestMinutes : Table("usage_ingest_minutes") {
    val orgId = uuid("org_id")
    val memberId = uuid("member_id")

    /** Minutes since the epoch. */
    val minute = long("minute")
    val events = integer("events")

    override val primaryKey = PrimaryKey(orgId, memberId, minute)
}

/** A `DATE` as a [LocalDate], which pgjdbc binds as a date; read from a `java.sql.Date` or a [LocalDate]. */
internal class DateColumnType : ColumnType<LocalDate>() {
    override fun sqlType(): String = "DATE"

    override fun valueFromDB(value: Any): LocalDate = LocalDate.parse(value.toString())

    // Quoted where Exposed inlines a value (an expression's text, a logged statement): a date, never arithmetic.
    override fun nonNullValueToString(value: LocalDate): String = "'$value'"
}
