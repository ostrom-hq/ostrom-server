// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.usage.infrastructure

import run.ostrom.server.kernel.database.databaseNow
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import org.jetbrains.exposed.v1.core.AbstractQuery
import org.jetbrains.exposed.v1.core.Cast
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.DoubleColumnType
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.LessOp
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.QueryParameter
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.doubleLiteral
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.div
import org.jetbrains.exposed.v1.core.longLiteral
import org.jetbrains.exposed.v1.core.statements.Statement
import org.jetbrains.exposed.v1.core.statements.StatementBuilder
import org.jetbrains.exposed.v1.core.statements.buildStatement
import org.jetbrains.exposed.v1.core.stringLiteral
import org.jetbrains.exposed.v1.core.wrapAsExpression
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.statements.toExecutable
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant
import kotlin.uuid.Uuid

/**
 * Takes the advisory lock of [key] until the transaction ends: `SELECT pg_advisory_xact_lock(hashtextextended(?, 0))`,
 * the lock the ledger always took, so that a replica of the former version and one of this take turns while they
 * both serve.
 *
 * @param key What is locked: an org's holds.
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
 *
 * @return What it returns: the rows changed of a write.
 */
internal fun <T : Any> JdbcTransaction.write(body: StatementBuilder.() -> Statement<T>): T? =
    buildStatement(body).toExecutable().execute(this)

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
}

/** [this] as a `BIGINT` (`CAST(… AS BIGINT)`): PostgreSQL sums a `BIGINT` column as a `NUMERIC`. */
internal fun Expression<*>.asBigint(): ExpressionWithColumnType<Long> = Cast(this, LongColumnType())

/** The sum a one-column [query] reads, 0 when it has no row: `COALESCE((SELECT SUM(…) …), 0)`. */
internal fun sumOrZero(query: AbstractQuery<*>): ExpressionWithColumnType<Long> =
    CustomFunction("COALESCE", LongColumnType(), wrapAsExpression<Long>(query), longLiteral(0))

/**
 * The minute of the database's clock, as a number of minutes since the epoch:
 * `CAST(FLOOR(date_part('epoch', now()) / 60) AS BIGINT)`, the same minute on every replica.
 */
internal fun databaseMinute(): ExpressionWithColumnType<Long> {
    val epoch = CustomFunction("date_part", DoubleColumnType(), stringLiteral("epoch"), databaseNow())
    return CustomFunction("FLOOR", DoubleColumnType(), epoch / doubleLiteral(SECONDS_A_MINUTE)).asBigint()
}

private const val SECONDS_A_MINUTE = 60.0

/** A row of expressions, `(a, b)`, compared as a whole ([rowLess]). */
private class Row(private val parts: List<Expression<*>>) : Expression<Any>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) = queryBuilder {
        parts.appendTo(prefix = "(", postfix = ")") { +it }
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
internal fun rowLess(left: List<Expression<*>>, right: List<Expression<*>>): Op<Boolean> =
    LessOp(Row(left), Row(right))

/**
 * The first UTC midnight at or after [since], TeamCode #163: the spend of the whole days from it is read from
 * `usage_spend_days`, the calls before it from the ledger. [since] is taken to the microsecond first, rounded as the
 * driver sends it, so that a midnight is recognized as PostgreSQL stores it.
 *
 * @property day Its date.
 * @property at Its instant.
 */
internal class FirstWholeDay(since: Instant) {
    val day: LocalDate
    val at: Instant

    init {
        val micros = since.toJavaInstant().plusNanos(HALF_A_MICROSECOND).truncatedTo(ChronoUnit.MICROS)
        // A day less a microsecond later, truncated: the same day for a midnight, the next one otherwise.
        val later = micros.plusNanos(1.days.inWholeNanoseconds - NANOS_A_MICROSECOND)
        day = later.atOffset(ZoneOffset.UTC).toLocalDate()
        at = day.atStartOfDay(ZoneOffset.UTC).toInstant().toKotlinInstant()
    }

    private companion object {
        const val HALF_A_MICROSECOND = 500L
        const val NANOS_A_MICROSECOND = 1_000L
    }
}

/**
 * Whose spend a sum reads: the whole org, one member's, or one workflow's.
 *
 * @property memberId The member, or null.
 * @property workflowId The workflow, or null.
 */
internal data class Spender(val memberId: Uuid? = null, val workflowId: String? = null) {
    /** The filter on [UsageEvents], none for the org. */
    fun ofEvents(): Op<Boolean>? = filter({ UsageEvents.memberId eq it }, { UsageEvents.workflowId eq it })

    /** The filter on [UsageSpendDays], none for the org. */
    fun ofDays(): Op<Boolean>? = filter({ UsageSpendDays.memberId eq it }, { UsageSpendDays.workflowId eq it })

    /** The filter on [UsageHolds], none for the org. */
    fun ofHolds(): Op<Boolean>? = filter({ UsageHolds.memberId eq it }, { UsageHolds.workflowId eq it })

    private fun filter(member: (Uuid) -> Op<Boolean>, workflow: (String) -> Op<Boolean>): Op<Boolean>? = when {
        memberId != null -> member(memberId)
        workflowId != null -> workflow(workflowId)
        else -> null
    }

    /** The whole org's. */
    companion object {
        /** The whole org's. */
        val ORG = Spender()
    }
}
