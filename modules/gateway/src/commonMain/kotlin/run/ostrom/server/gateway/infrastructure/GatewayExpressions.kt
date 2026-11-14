// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway.infrastructure

import org.jetbrains.exposed.v1.core.Count
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.Max
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryParameter
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.case
import org.jetbrains.exposed.v1.core.coalesce
import org.jetbrains.exposed.v1.core.intLiteral
import org.jetbrains.exposed.v1.core.longLiteral
import org.jetbrains.exposed.v1.core.statements.Statement
import org.jetbrains.exposed.v1.core.statements.StatementBuilder
import org.jetbrains.exposed.v1.core.statements.buildStatement
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.statements.toExecutable

/**
 * The rows of the group where [condition] holds: `COUNT(CASE WHEN condition THEN 1 END)`, PostgreSQL's
 * `count(*) FILTER (WHERE condition)`.
 */
internal fun countWhere(condition: Op<Boolean>): Count = Count(case().When(condition, intLiteral(1)))

/**
 * The largest [value] of the rows where [condition] holds, 0 when none: `COALESCE(MAX(CASE WHEN condition THEN value
 * END), 0)`.
 */
internal fun maxWhere(condition: Op<Boolean>, value: Expression<Long>): ExpressionWithColumnType<Long> =
    coalesce(Max<Long, Long?>(case().When(condition, value), LongColumnType()), longLiteral(0))

/** The largest [value] of the group, 0 when it has no row. */
internal fun maxOrZero(value: Expression<Long>): ExpressionWithColumnType<Long> =
    coalesce(Max<Long, Long>(value, LongColumnType()), longLiteral(0))

/**
 * Takes the advisory lock of [key] until the transaction ends: `SELECT pg_advisory_xact_lock(hashtextextended(?, 0))`,
 * the lock the gateway always took, so that a replica of the former version and one of this take turns while they
 * both serve.
 *
 * @param key What is locked: an org's admissions or cancellation markers.
 *
 * @return The read that takes it, for a plan; [lockUntilCommit] runs it.
 */
internal fun advisoryLock(key: String): Query {
    val hash = CustomFunction(
        "hashtextextended",
        LongColumnType(),
        QueryParameter(key, TextColumnType()),
        longLiteral(0),
    )
    return Table.Dual.select(CustomFunction("pg_advisory_xact_lock", TextColumnType(), hash))
}

/**
 * Takes the advisory lock of [key] ([advisoryLock]) now, until this transaction ends.
 *
 * @param key What is locked.
 */
internal fun JdbcTransaction.lockUntilCommit(key: String) {
    advisoryLock(key).toList()
}

/**
 * Runs one write built by [body] in this transaction.
 *
 * @param body The statement.
 */
internal fun <T : Any> JdbcTransaction.write(body: StatementBuilder.() -> Statement<T>) {
    buildStatement(body).toExecutable().execute(this)
}

/**
 * A read and how its rows become what a repository decides on: the [query] alone for a plan.
 *
 * @property query What is sent.
 */
internal class Read<T>(val query: Query, private val map: (ResultRow) -> T) {
    /** The rows, mapped. */
    fun rows(): List<T> = query.map(map)

    /** The one row of an aggregate. */
    fun single(): T = map(query.single())

    /** The row, if any. */
    fun singleOrNull(): T? = query.singleOrNull()?.let(map)
}
