// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.database

import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.CustomOperator
import org.jetbrains.exposed.v1.core.DoubleColumnType
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.QueryParameter
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.intLiteral
import kotlin.time.Instant

/**
 * The database's clock (`now()`, the transaction's start): leases, windows and expiries shared by every replica are
 * timed by it, never by a replica's clock.
 *
 * @return The expression.
 */
fun databaseNow(): Expression<Instant> = CustomFunction("now", InstantColumnType())

/**
 * The database's clock moved by [seconds] (`now() + make_interval(0, 0, 0, 0, 0, 0, ?)`, the seconds bound, negative
 * for the past): a lease's end, or the oldest time still alive.
 *
 * @param seconds How far from now.
 *
 * @return The expression.
 */
fun databaseNowPlus(seconds: Long): Expression<Instant> {
    val zero = intLiteral(0)
    val interval = CustomFunction(
        "make_interval",
        TextColumnType(),
        zero, zero, zero, zero, zero, zero, QueryParameter(seconds.toDouble(), DoubleColumnType()),
    )
    return CustomOperator("+", InstantColumnType(), databaseNow(), interval)
}
