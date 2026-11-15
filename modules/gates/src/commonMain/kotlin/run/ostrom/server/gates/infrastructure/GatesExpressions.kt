// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gates.infrastructure

import org.jetbrains.exposed.v1.core.AbstractQuery
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.LessOp
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.QueryParameter
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.longLiteral
import org.jetbrains.exposed.v1.core.statements.Statement
import org.jetbrains.exposed.v1.core.statements.StatementBuilder
import org.jetbrains.exposed.v1.core.statements.buildStatement
import org.jetbrains.exposed.v1.core.wrapAsExpression
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.statements.toExecutable

/**
 * Takes the advisory lock of [key] until the transaction ends: `SELECT pg_advisory_xact_lock(hashtextextended(?, 0))`,
 * the lock the gates always took, so that a replica of the former version and one of this take turns while they both
 * serve (the gateway's `advisoryLock`).
 *
 * @param key What is locked: an action's key, or a session.
 *
 * @return The read that takes it, for a plan; [lockKeyUntilCommit] runs it.
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
internal fun JdbcTransaction.lockKeyUntilCommit(key: String) {
    advisoryLock(key).toList()
}

/**
 * Runs one write built by [body] in this transaction: the plan tests explain the same builder.
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

    /** The first row, if any. */
    fun firstOrNull(): T? = query.firstOrNull()?.let(map)
}

/**
 * The row `(a, b, …)` of [parts], compared as one value: `(created_at, id) < (SELECT created_at, id …)` is the
 * keyset cursor of a page, one index condition.
 */
private class RowOf(private val parts: List<Expression<*>>) : Expression<Any>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) = queryBuilder {
        parts.appendTo(prefix = "(", postfix = ")") { append(it) }
    }
}

/**
 * `(parts…) < (subquery)`: the rows before the one row [subquery] reads, in the order of [parts].
 *
 * @param parts The columns, in their order.
 * @param subquery The read of the same columns of the cursor's row.
 *
 * @return The condition; false when the subquery reads no row.
 */
internal fun rowBefore(parts: List<Expression<*>>, subquery: AbstractQuery<*>): Op<Boolean> =
    LessOp(RowOf(parts), wrapAsExpression<Any>(subquery))
