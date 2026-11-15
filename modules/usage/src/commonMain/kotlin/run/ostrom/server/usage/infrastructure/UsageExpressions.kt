// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.usage.infrastructure

import run.ostrom.server.kernel.database.asBigint
import run.ostrom.server.kernel.database.databaseNow
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.DoubleColumnType
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.div
import org.jetbrains.exposed.v1.core.doubleLiteral
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.stringLiteral
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant
import kotlin.uuid.Uuid

/**
 * The minute of the database's clock, as a number of minutes since the epoch:
 * `CAST(FLOOR(date_part('epoch', now()) / 60) AS BIGINT)`, the same minute on every replica.
 */
internal fun databaseMinute(): ExpressionWithColumnType<Long> {
    val epoch = CustomFunction("date_part", DoubleColumnType(), stringLiteral("epoch"), databaseNow())
    return CustomFunction("FLOOR", DoubleColumnType(), epoch / doubleLiteral(SECONDS_A_MINUTE)).asBigint()
}

private const val SECONDS_A_MINUTE = 60.0

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
