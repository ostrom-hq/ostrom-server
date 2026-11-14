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
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.case
import org.jetbrains.exposed.v1.core.coalesce
import org.jetbrains.exposed.v1.core.intLiteral
import org.jetbrains.exposed.v1.core.longLiteral
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.select

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
 * @return The read that takes it.
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
