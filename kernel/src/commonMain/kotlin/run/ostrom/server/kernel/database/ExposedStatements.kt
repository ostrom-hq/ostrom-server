// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.database

import org.jetbrains.exposed.v1.core.AbstractQuery
import org.jetbrains.exposed.v1.core.Cast
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Count
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.LessOp
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.Max
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
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
import org.jetbrains.exposed.v1.core.wrapAsExpression
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.statements.toExecutable

// The typed statements every module on Exposed shares (docs/MODULES.md section 4): a read and its mapping, one write,
// the advisory lock of a key, and the expressions Exposed's DSL lacks for PostgreSQL. Moved from the gateway, usage
// and gates, which each had a copy.

/**
 * The read that takes the advisory lock of [key] until the transaction ends:
 * `SELECT pg_advisory_xact_lock(hashtextextended(?, 0))`, the lock the sqlx4k repositories took, so that a replica
 * of the former version and one of this take turns while they both serve. Not the audit chain's lock
 * ([lockUntilCommit], `hashtext`): a module keeps the hash its sqlx4k repository used.
 *
 * @param key What is locked: an org's admissions, holds, an action's key…
 *
 * @return The read, for a plan; [lockKeyUntilCommit] runs it.
 */
fun advisoryLock(key: String): Query {
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
fun JdbcTransaction.lockKeyUntilCommit(key: String) {
    advisoryLock(key).toList()
}

/**
 * Runs one write built by [body] in this transaction: the plan tests explain the same builder.
 *
 * @param body The statement.
 *
 * @return What it returns: the rows an update or a delete changed.
 */
fun <T : Any> JdbcTransaction.write(body: StatementBuilder.() -> Statement<T>): T? =
    buildStatement(body).toExecutable().execute(this)

/**
 * The value of a nullable [column] that this row holds by construction, written together with [with], the one the
 * caller checked (a key's rotation has its signature, a used link its account and its date). A row that breaks it
 * fails here, the column named, rather than later on a null.
 *
 * @param column The column.
 * @param with The column it is written with.
 *
 * @return Its value.
 */
fun <T : Any> ResultRow.present(column: Column<T?>, with: Column<*>): T =
    checkNotNull(this[column]) { "${column.name} is set with ${with.name}" }

/**
 * A read and how its rows become what a repository decides on: the [query] alone for a plan.
 *
 * @property query What is sent.
 */
class Read<T>(val query: Query, private val map: (ResultRow) -> T) {
    /** The rows, mapped. */
    fun rows(): List<T> = query.map(map)

    /** The one row of an aggregate. */
    fun single(): T = map(query.single())

    /** The row, if any. */
    fun singleOrNull(): T? = query.singleOrNull()?.let(map)

    /** The first row, if any. */
    fun firstOrNull(): T? = query.firstOrNull()?.let(map)
}

/**
 * The rows of the group where [condition] holds: `COUNT(CASE WHEN condition THEN 1 END)`, PostgreSQL's
 * `count(*) FILTER (WHERE condition)`.
 */
fun countWhere(condition: Op<Boolean>): Count = Count(case().When(condition, intLiteral(1)))

/**
 * The largest [value] of the rows where [condition] holds, 0 when none: `COALESCE(MAX(CASE WHEN condition THEN value
 * END), 0)`.
 */
fun maxWhere(condition: Op<Boolean>, value: Expression<Long>): ExpressionWithColumnType<Long> =
    coalesce(Max<Long, Long?>(case().When(condition, value), LongColumnType()), longLiteral(0))

/** The largest [value] of the group, 0 when it has no row. */
fun maxOrZero(value: Expression<Long>): ExpressionWithColumnType<Long> =
    coalesce(Max<Long, Long>(value, LongColumnType()), longLiteral(0))

/** [this] as a `BIGINT` (`CAST(… AS BIGINT)`): PostgreSQL sums a `BIGINT` column as a `NUMERIC`. */
fun Expression<*>.asBigint(): ExpressionWithColumnType<Long> = Cast(this, LongColumnType())

/** The sum a one-column [query] reads, 0 when it has no row: `COALESCE((SELECT SUM(…) …), 0)`. */
fun sumOrZero(query: AbstractQuery<*>): ExpressionWithColumnType<Long> =
    CustomFunction("COALESCE", LongColumnType(), wrapAsExpression<Long>(query), longLiteral(0))

/** A row of expressions, `(a, b)`, compared as a whole ([rowLess], [rowBefore]). */
private class RowOf(private val parts: List<Expression<*>>) : Expression<Any>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) = queryBuilder {
        parts.appendTo(prefix = "(", postfix = ")") { append(it) }
    }
}

/**
 * `(a, b) < (x, y)`: PostgreSQL compares the rows in order, as one index condition on an index of `(a, b)`.
 *
 * @param left The columns.
 * @param right Their bounds.
 *
 * @return The condition.
 */
fun rowLess(left: List<Expression<*>>, right: List<Expression<*>>): Op<Boolean> = LessOp(RowOf(left), RowOf(right))

/**
 * `(parts…) < (subquery)`: the rows before the one row [subquery] reads, in the order of [parts]. The keyset cursor
 * of a page, `(created_at, id) < (SELECT created_at, id …)`, one index condition.
 *
 * @param parts The columns, in their order.
 * @param subquery The read of the same columns of the cursor's row.
 *
 * @return The condition; false when the subquery reads no row.
 */
fun rowBefore(parts: List<Expression<*>>, subquery: AbstractQuery<*>): Op<Boolean> =
    LessOp(RowOf(parts), wrapAsExpression<Any>(subquery))
