// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.events.infrastructure

import run.ostrom.server.kernel.database.InstantColumnType
import org.jetbrains.exposed.v1.core.ColumnSet
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.QueryParameter
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.appendTo
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.time.Instant

// The functions of `ostrom_router` the module calls across organizations (EventsMigrations versions 1 to 4), their
// arguments bound by Exposed.

/** What `events_readers_behind(reader, limit)` returns, read as a table: the readers' positions behind a head. */
internal object EventsReadersBehind : Table("events_readers_behind") {
    val orgId = uuid("org_id")
    val fromSeq = long("from_seq")
    val head = long("head")

    /**
     * The function called for [reader], at most [limit] organizations.
     *
     * @param reader The reader.
     * @param limit The most organizations.
     */
    fun of(reader: String, limit: Int): ColumnSet = FunctionCall(tableName, listOf(textParam(reader), intParam(limit)))
}

/** What `events_webhook_orgs(now, limit)` returns, read as a table: the organizations whose webhooks have work. */
internal object EventsWebhookOrgs : Table("events_webhook_orgs") {
    val orgId = uuid("org_id")

    /**
     * The function called at [now], at most [limit] organizations.
     *
     * @param now The time.
     * @param limit The most organizations.
     */
    fun of(now: Instant, limit: Int): ColumnSet = FunctionCall(tableName, listOf(instantParam(now), intParam(limit)))
}

/** A set-returning function called with its [arguments], read as a table of its own name. */
private class FunctionCall(name: String, private val arguments: List<Expression<*>>) : Table(name) {
    override fun describe(s: Transaction, queryBuilder: QueryBuilder): Unit = queryBuilder {
        append(tableName)
        arguments.appendTo(this, prefix = "(", postfix = ")") { +it }
    }
}

/**
 * Calls a function of `ostrom_router` that returns a count (`SELECT name(args…)`).
 *
 * @param name The function.
 * @param arguments Its arguments.
 *
 * @return The count it returns.
 */
internal fun JdbcTransaction.countOf(name: String, vararg arguments: Expression<*>): Long {
    val call = CustomFunction(name, LongColumnType(), *arguments)
    return Table.Dual.select(call).single()[call]
}

/** [value] bound as a `TEXT`. */
internal fun textParam(value: String): Expression<String> = QueryParameter(value, TextColumnType())

/** [value] bound as an `INTEGER`. */
internal fun intParam(value: Int): Expression<Int> = QueryParameter(value, IntegerColumnType())

/** [value] bound as a `TIMESTAMPTZ`. */
internal fun instantParam(value: Instant): Expression<Instant> = QueryParameter(value, InstantColumnType())
