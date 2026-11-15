// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.automations.infrastructure

import run.ostrom.server.kernel.database.InstantColumnType
import run.ostrom.server.kernel.database.instant
import org.jetbrains.exposed.v1.core.ColumnSet
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.QueryParameter
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.UuidColumnType
import org.jetbrains.exposed.v1.core.appendTo
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.time.Instant
import kotlin.uuid.Uuid

// The functions of `ostrom_router` the module calls across organizations (AutomationsMigrations versions 3, 6 and 9),
// their arguments bound by Exposed.

/** What `automations_due_routes(now, limit)` returns, read as a table: the due crons, their routes locked. */
internal object AutomationsDueRoutes : Table("automations_due_routes") {
    val automationId = uuid("automation_id")
    val orgId = uuid("org_id")
    val nextRunAt = instant("next_run_at")

    /**
     * The function called at [now], at most [limit] routes.
     *
     * @param now The time.
     * @param limit The most routes.
     */
    fun of(now: Instant, limit: Int): ColumnSet = FunctionCall(tableName, listOf(instantParam(now), intParam(limit)))
}

/** What `automations_organizations()` returns (a set of UUIDs, its column named as the function), as a table. */
internal object AutomationsOrganizations : Table("automations_organizations") {
    val orgId = uuid("automations_organizations")

    /** The function called. */
    fun all(): ColumnSet = FunctionCall(tableName, emptyList())
}

/** What `automations_due_waits(now, limit)` returns, read as a table: the ids of the waits whose instant has come. */
internal object AutomationsDueWaits : Table("automations_due_waits") {
    val id = uuid("id")
    val orgId = uuid("org_id")
    val resolver = text("resolver")

    /**
     * The function called at [now], at most [limit] waits.
     *
     * @param now The time.
     * @param limit The most waits.
     */
    fun of(now: Instant, limit: Int): ColumnSet = FunctionCall(tableName, listOf(instantParam(now), intParam(limit)))
}

/** What `automations_wait_event(id)` returns, read as a table: the organization and secret hash of an event address. */
internal object AutomationsWaitEvent : Table("automations_wait_event") {
    val orgId = uuid("org_id")
    val secretHash = text("secret_hash")

    /**
     * The function called for the wait [id].
     *
     * @param id The wait.
     */
    fun of(id: Uuid): ColumnSet = FunctionCall(tableName, listOf(QueryParameter(id, UuidColumnType())))
}

/** `automations_route_org(id)`: the organization of an automation's route, null when it has none. */
internal fun routeOrg(id: Uuid): CustomFunction<Uuid?> =
    CustomFunction("automations_route_org", UuidColumnType(), QueryParameter(id, UuidColumnType()))

/**
 * `automations_purge_wait_secrets(now, limit)`: deletes one batch of the expired secrets of every organization and
 * returns how many.
 *
 * @param now The time.
 * @param limit The most secrets.
 */
internal fun purgeWaitSecrets(now: Instant, limit: Int): CustomFunction<Long> =
    CustomFunction("automations_purge_wait_secrets", LongColumnType(), instantParam(now), intParam(limit))

/** The one value of a function [call] that returns a scalar (`SELECT name(args…)`). */
internal fun <T> JdbcTransaction.scalar(call: CustomFunction<T>): T = Table.Dual.select(call).single()[call]

/** A set-returning function called with its [arguments], read as a table of its own name. */
private class FunctionCall(name: String, private val arguments: List<Expression<*>>) : Table(name) {
    override fun describe(s: Transaction, queryBuilder: QueryBuilder): Unit = queryBuilder {
        append(tableName)
        arguments.appendTo(this, prefix = "(", postfix = ")") { +it }
    }
}

/** [value] bound as an `INTEGER`. */
internal fun intParam(value: Int): Expression<Int> = QueryParameter(value, IntegerColumnType())

/** [value] bound as a `TIMESTAMPTZ`. */
internal fun instantParam(value: Instant): Expression<Instant> = QueryParameter(value, InstantColumnType())
