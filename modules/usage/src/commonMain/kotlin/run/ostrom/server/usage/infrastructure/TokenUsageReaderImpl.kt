// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.usage.infrastructure

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.Read
import run.ostrom.server.kernel.database.asBigint
import run.ostrom.server.usage.domain.TokenBucket
import run.ostrom.server.usage.domain.TokenUsageReader
import run.ostrom.server.usage.domain.UsageKind
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.DoubleColumnType
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.coalesce
import org.jetbrains.exposed.v1.core.div
import org.jetbrains.exposed.v1.core.doubleLiteral
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.longLiteral
import org.jetbrains.exposed.v1.core.stringLiteral
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [TokenUsageReader] on the ledger, each read confined to the org it names ([JdbcDatabase.inOrg]).
 *
 * @param database The JDBC pool.
 */
class TokenUsageReaderImpl(private val database: JdbcDatabase) : TokenUsageReader {
    override suspend fun accountHours(orgId: Uuid, accountId: Uuid, since: Instant, limit: Int): List<TokenBucket> =
        database.inOrg(orgId) { accountHoursRead(orgId, accountId, since, limit).rows() }

    override suspend fun orgDays(orgId: Uuid, since: Instant, limit: Int): List<TokenBucket> =
        database.inOrg(orgId) { orgDaysRead(orgId, since, limit).rows() }
}

/** One shared account's model tokens by UTC hour (index `usage_events_account_at`), newest hours first. */
internal fun accountHoursRead(orgId: Uuid, accountId: Uuid, since: Instant, limit: Int): Read<TokenBucket> {
    val e = UsageEvents
    return bucketsRead(HOUR_SECONDS, limit) {
        (e.accountId eq accountId) and (e.orgId eq orgId) and (e.at greaterEq since)
    }
}

/** The org's model tokens by UTC day (index `usage_events_org_at`), newest days first. */
internal fun orgDaysRead(orgId: Uuid, since: Instant, limit: Int): Read<TokenBucket> {
    val e = UsageEvents
    return bucketsRead(DAY_SECONDS, limit) { (e.orgId eq orgId) and (e.at greaterEq since) }
}

private fun bucketsRead(seconds: Double, limit: Int, where: () -> Op<Boolean>): Read<TokenBucket> {
    val e = UsageEvents
    val bucket = bucketOf(seconds)
    val input = total(e.inputTokens)
    val output = total(e.outputTokens)
    val cacheRead = total(e.cacheReadTokens)
    val cacheWrite = total(e.cacheWriteTokens)
    val query = e.select(bucket, e.provider, e.name, input, output, cacheRead, cacheWrite)
        .where { where() and (e.kind eq UsageKind.MODEL.name) }
        .groupBy(bucket, e.provider, e.name)
        // Past the bound, whole recent buckets rather than an arbitrary subset (TeamCode's reads).
        .orderBy(bucket to SortOrder.DESC)
        .limit(limit)
    return Read(query) {
        TokenBucket(it[bucket], it[e.provider], it[e.name], it[input], it[output], it[cacheRead], it[cacheWrite])
    }
}

/** `CAST(FLOOR(date_part('epoch', at) / seconds) AS BIGINT)`: the hour or day number of a call, in UTC. */
private fun bucketOf(seconds: Double): ExpressionWithColumnType<Long> {
    val epoch = CustomFunction("date_part", DoubleColumnType(), stringLiteral("epoch"), UsageEvents.at)
    return CustomFunction("FLOOR", DoubleColumnType(), epoch / doubleLiteral(seconds)).asBigint()
}

private fun total(column: ExpressionWithColumnType<Long>): ExpressionWithColumnType<Long> =
    coalesce(column.sum(), longLiteral(0)).asBigint()

private const val HOUR_SECONDS = 3_600.0
private const val DAY_SECONDS = 86_400.0
