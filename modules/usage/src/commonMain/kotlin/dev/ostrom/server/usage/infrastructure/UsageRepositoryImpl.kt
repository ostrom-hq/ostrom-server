// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.usage.infrastructure

import dev.ostrom.server.secrets.infrastructure.executeInOrg
import dev.ostrom.server.secrets.infrastructure.fetchInOrg
import dev.ostrom.server.secrets.infrastructure.inTenant
import dev.ostrom.server.usage.domain.Admission
import dev.ostrom.server.usage.domain.HoldWindow
import dev.ostrom.server.usage.domain.SpendHold
import dev.ostrom.server.usage.domain.SpendLimits
import dev.ostrom.server.usage.domain.SpendTotals
import dev.ostrom.server.usage.domain.UsageCursor
import dev.ostrom.server.usage.domain.UsageEvent
import dev.ostrom.server.usage.domain.UsageGroupBy
import dev.ostrom.server.usage.domain.UsageKind
import dev.ostrom.server.usage.domain.UsageRepository
import dev.ostrom.server.usage.domain.UsageTotal
import dev.ostrom.server.usage.domain.UsageWindow
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuidOrNull
import kotlin.reflect.KClass
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [UsageRepository] on PostgreSQL (table of [UsageMigrations]). Sums are cast to `BIGINT` (PostgreSQL sums a
 * `BIGINT` column as `NUMERIC`).
 *
 * @param driver The database.
 */
class UsageRepositoryImpl(private val driver: Driver) : UsageRepository {
    override suspend fun insert(orgId: Uuid, events: List<UsageEvent>) {
        if (events.isEmpty()) return
        // Parameters are named e<index>_<column>: ":e1_id" is never a prefix of ":e10_id".
        val rows = events.indices.joinToString(", ") { index ->
            PARAMETERS.joinToString(", ", prefix = "(", postfix = ")") { ":e${index}_$it" }
        }
        val statement = Statement.create("INSERT INTO usage_events ($COLUMNS) VALUES $rows")
        events.forEachIndexed { index, event -> statement.bindEvent("e${index}_", event) }
        // Under the org's policy, a row of another org is refused (WITH CHECK).
        driver.executeInOrg(orgId, statement).getOrThrow()
    }

    override suspend fun settle(event: UsageEvent) {
        val insert = Statement.create("INSERT INTO usage_events ($COLUMNS) VALUES ($ONE_ROW)").bindEvent("", event)
        driver.inTenant(event.orgId) {
            execute(insert).getOrThrow()
            execute(Statement.create(UsageQueries.DROP_HOLD).bind("call_id", event.id)).getOrThrow()
        }
    }

    override suspend fun holdWithin(hold: SpendHold, limits: SpendLimits, window: HoldWindow): Admission =
        // One transaction on one connection: the lock is held until its end, and no read waits for another connection.
        driver.inTenant(hold.orgId) {
            fetchAll(Statement.create(UsageQueries.LOCK_HOLDS).bind("key", "usage_holds:${hold.orgId}")).getOrThrow()
            execute(
                Statement.create(UsageQueries.DROP_EXPIRED_HOLDS).bind("org_id", hold.orgId).bind("now", window.now),
            ).getOrThrow()
            val row = fetchAll(holdTotals(hold, limits, window)).getOrThrow().first()
            val totals = SpendTotals(
                orgSpent = row.get("org_spent").asLong(),
                orgHeld = row.get("org_held").asLong(),
                memberSpent = row.get("member_spent").asLong(),
                memberHeld = row.get("member_held").asLong(),
                orgDaySpent = row.get("org_day_spent").asLong(),
                memberDaySpent = row.get("member_day_spent").asLong(),
                workflowSpent = row.get("workflow_spent").asLong(),
                workflowHeld = row.get("workflow_held").asLong(),
                workflowDaySpent = row.get("workflow_day_spent").asLong(),
            )
            limits.admit(totals, hold.micros).also { admission ->
                if (admission == Admission.Admitted) {
                    val put = Statement.create(UsageQueries.PUT_HOLD).bind("call_id", hold.callId)
                        .bind("org_id", hold.orgId).bind("member_id", hold.memberId).bind("micros", hold.micros)
                        .bindNullable("workflow_id", hold.workflowId, String::class)
                        .bind("expires_at", window.expiresAt)
                    execute(put).getOrThrow()
                }
            }
        }

    override suspend fun spentMicros(orgId: Uuid, memberId: Uuid?, since: Instant): Long {
        val statement = Statement.create(UsageQueries.spent(member = memberId != null))
            .bind("org_id", orgId).bind("since", since)
        if (memberId != null) statement.bind("member_id", memberId)
        return driver.fetchInOrg(orgId, statement).getOrThrow().first().get("spent").asLong()
    }

    override suspend fun accountSpentMicros(orgId: Uuid, accountId: Uuid, since: Instant): Long = driver.fetchInOrg(
        orgId,
        Statement.create(
            UsageQueries.ACCOUNT_SPENT,
        ).bind("org_id", orgId).bind("account_id", accountId).bind("since", since),
    ).getOrThrow().first().get("spent").asLong()

    override suspend fun workflowSpentMicros(orgId: Uuid, workflowId: String, since: Instant): Long =
        driver.fetchInOrg(
            orgId,
            Statement.create(UsageQueries.WORKFLOW_SPENT)
                .bind("org_id", orgId).bind("workflow_id", workflowId).bind("since", since),
        ).getOrThrow().first().get("spent").asLong()

    override suspend fun launchSpentMicros(orgId: Uuid, launchId: String, from: Instant, to: Instant): Long? =
        driver.fetchInOrg(
            orgId,
            Statement.create(UsageQueries.LAUNCH_SPENT)
                .bind("org_id", orgId).bind("launch_id", launchId).bind("from", from).bind("to", to),
        ).getOrThrow().first().get("spent").asStringOrNull()?.toLong()

    override suspend fun summary(window: UsageWindow, groupBy: UsageGroupBy, limit: Int): List<UsageTotal> {
        val sql = UsageQueries.summary(groupBy, until = window.until != null, member = window.memberId != null)
        val statement = Statement.create(
            sql,
        ).bind("org_id", window.orgId).bind("since", window.since).bind("limit", limit)
        window.memberId?.let { statement.bind("member_id", it) }
        window.until?.let { statement.bind("until", it) }
        return driver.fetchInOrg(window.orgId, statement).getOrThrow().map { row ->
            UsageTotal(
                key = row.get("group_key").asStringOrNull(),
                events = row.get("events").asLong(),
                inputTokens = row.get("input_tokens").asLong(),
                outputTokens = row.get("output_tokens").asLong(),
                cacheReadTokens = row.get("cache_read_tokens").asLong(),
                cacheWriteTokens = row.get("cache_write_tokens").asLong(),
                costMicros = row.get("total_cost").asLong(),
            )
        }
    }

    override suspend fun list(orgId: Uuid, memberId: Uuid?, before: UsageCursor?, limit: Int): List<UsageEvent> {
        val cursor = when {
            before == null -> UsageQueries.Cursor.NONE
            before.id == null -> UsageQueries.Cursor.AT
            else -> UsageQueries.Cursor.AT_AND_ID
        }
        val statement = Statement.create(UsageQueries.list(member = memberId != null, cursor))
            .bind("org_id", orgId).bind("limit", limit)
        if (memberId != null) statement.bind("member_id", memberId)
        if (before != null) {
            statement.bind("before_at", before.at)
            if (before.id != null) statement.bind("before_id", before.id)
        }
        return driver.fetchInOrg(orgId, statement).getOrThrow().map { it.toEvent() }
    }

    // The one operation across orgs, through the function of UsageMigrations version 4: one batch of old events.
    override suspend fun purge(before: Instant, limit: Int): Long = driver.fetchAll(
        Statement.create("SELECT usage_purge(CAST(:before AS TIMESTAMPTZ), CAST(:limit AS INTEGER)) AS n")
            .bind("before", before).bind("limit", limit),
    ).getOrThrow().first().get("n").asLong()

}

// Top level rather than in a companion: a private companion leaves a synthetic constructor no test can reach.
private val PARAMETERS = listOf(
    "id", "org_id", "member_id", "kind", "name", "provider", "account_id", "session_id", "launch_id", "workflow_id",
    "input_tokens", "output_tokens", "cache_read_tokens", "cache_write_tokens", "cost_micros", "duration_ms", "outcome",
    "at",
)
internal val COLUMNS = PARAMETERS.joinToString(", ")
private val ONE_ROW = PARAMETERS.joinToString(", ") { ":$it" }

private fun Statement.bindEvent(prefix: String, event: UsageEvent): Statement = bind("${prefix}id", event.id)
    .bind("${prefix}org_id", event.orgId)
    .bindNullable("${prefix}member_id", event.memberId, Uuid::class)
    .bind("${prefix}kind", event.kind.name)
    .bind("${prefix}name", event.name)
    .bindNullable("${prefix}provider", event.provider, String::class)
    .bindNullable("${prefix}account_id", event.accountId, Uuid::class)
    .bindNullable("${prefix}session_id", event.sessionId, String::class)
    .bindNullable("${prefix}launch_id", event.launchId, String::class)
    .bindNullable("${prefix}workflow_id", event.workflowId, String::class)
    .bind("${prefix}input_tokens", event.inputTokens)
    .bind("${prefix}output_tokens", event.outputTokens)
    .bind("${prefix}cache_read_tokens", event.cacheReadTokens)
    .bind("${prefix}cache_write_tokens", event.cacheWriteTokens)
    .bind("${prefix}cost_micros", event.costMicros)
    .bind("${prefix}duration_ms", event.durationMillis)
    .bind("${prefix}outcome", event.outcome)
    .bind("${prefix}at", event.at)

private fun Statement.bindNullable(name: String, value: Any?, type: KClass<*>): Statement =
    if (value == null) bindNull(name, type) else bind(name, value)

private fun ResultSet.Row.toEvent() = UsageEvent(
    id = get("id").asUuid(),
    orgId = get("org_id").asUuid(),
    memberId = get("member_id").asUuidOrNull(),
    kind = UsageKind.valueOf(get("kind").asString()),
    name = get("name").asString(),
    provider = get("provider").asStringOrNull(),
    accountId = get("account_id").asUuidOrNull(),
    sessionId = get("session_id").asStringOrNull(),
    launchId = get("launch_id").asStringOrNull(),
    workflowId = get("workflow_id").asStringOrNull(),
    inputTokens = get("input_tokens").asLong(),
    outputTokens = get("output_tokens").asLong(),
    cacheReadTokens = get("cache_read_tokens").asLong(),
    cacheWriteTokens = get("cache_write_tokens").asLong(),
    costMicros = get("cost_micros").asLong(),
    durationMillis = get("duration_ms").asLong(),
    outcome = get("outcome").asString(),
    at = get("at").asInstant(),
)

/** The sums a call is admitted on, each bound only when a limit needs it. */
private fun holdTotals(hold: SpendHold, limits: SpendLimits, window: HoldWindow): Statement {
    val org = limits.orgMicros != null
    val member = limits.memberMicros != null
    val daily = limits.daily
    val orgDay = daily?.orgMicros != null
    val memberDay = daily?.memberMicros != null
    // A workflow's limits apply only to a call of a launch that plays it; its sums are read only then.
    val workflow = hold.workflowId != null && limits.workflowMicros != null
    val workflowDay = hold.workflowId != null && daily?.workflowMicros != null
    val sums = Statement.create(UsageQueries.holdTotals(org, member, orgDay, memberDay, workflow, workflowDay))
        .bind("org_id", hold.orgId).bind("call_id", hold.callId).bind("now", window.now)
    if (org || member || workflow) sums.bind("since", window.since)
    if (daily != null) sums.bind("day_since", daily.since)
    if (member || memberDay) sums.bind("member_id", hold.memberId)
    hold.workflowId?.takeIf { workflow || workflowDay }?.let { sums.bind("workflow_id", it) }
    return sums
}
