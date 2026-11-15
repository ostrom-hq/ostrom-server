// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.database

import org.jetbrains.exposed.v1.core.AbstractQuery
import org.jetbrains.exposed.v1.core.Cast
import org.jetbrains.exposed.v1.core.Count
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.LessOp
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.Max
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.case
import org.jetbrains.exposed.v1.core.coalesce
import org.jetbrains.exposed.v1.core.intLiteral
import org.jetbrains.exposed.v1.core.longLiteral
import org.jetbrains.exposed.v1.core.wrapAsExpression

// The expressions Exposed's DSL lacks for PostgreSQL, shared by every module on Exposed (docs/MODULES.md section 4).

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
