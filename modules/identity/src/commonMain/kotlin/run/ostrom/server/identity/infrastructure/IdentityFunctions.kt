// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity.infrastructure

import run.ostrom.server.kernel.database.InstantColumnType
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.QueryParameter
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.UuidColumnType
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The database's clock (`now()`): the rate limits' windows are timed by it, never by a replica's. */
internal fun databaseNow(): Expression<Instant> = CustomFunction("now", InstantColumnType())

/** [value] bound as a `TIMESTAMPTZ`. */
internal fun instantParam(value: Instant): Expression<Instant> = QueryParameter(value, InstantColumnType())

/** [value] bound as a `UUID`. */
internal fun uuidParam(value: Uuid): Expression<Uuid> = QueryParameter(value, UuidColumnType())

/** [value] bound as a `TEXT`. */
internal fun textParam(value: String): Expression<String> = QueryParameter(value, TextColumnType())

/**
 * Calls a function of `ostrom_router` that returns a count (`SELECT name(args…)`, no table), its arguments bound.
 *
 * @param name The function (IdentityMigrations versions 6, 14 and 15).
 * @param arguments Its arguments.
 *
 * @return The count it returns.
 */
internal fun JdbcTransaction.countOf(name: String, vararg arguments: Expression<*>): Long {
    val call = CustomFunction(name, LongColumnType(), *arguments)
    return Table.Dual.select(call).single()[call]
}

/**
 * Calls a function of `ostrom_router` that returns nothing (`SELECT name(args…)`).
 *
 * @param name The function.
 * @param arguments Its arguments.
 */
internal fun JdbcTransaction.call(name: String, vararg arguments: Expression<*>) {
    Table.Dual.select(CustomFunction(name, TextColumnType(), *arguments)).toList()
}

/**
 * The value of a nullable [column] that this row holds by construction (written together with the one the caller
 * checked: an app's sign-in has all its three columns, a used setup link its account and its date).
 *
 * @param column The column.
 *
 * @return Its value, as the row holds it.
 */
@Suppress("UNCHECKED_CAST") // The column is nullable in general, never on the rows this is called for.
internal fun <T : Any> ResultRow.present(column: Column<T?>): T = this[column] as T
