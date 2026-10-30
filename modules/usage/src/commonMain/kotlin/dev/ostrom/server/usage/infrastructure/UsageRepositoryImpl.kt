// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.usage.infrastructure

import dev.ostrom.server.secrets.infrastructure.TENANT_ROLE
import dev.ostrom.server.secrets.infrastructure.executeInOrg
import dev.ostrom.server.secrets.infrastructure.fetchInOrg
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

    override suspend fun spentMicros(orgId: Uuid, memberId: Uuid?, since: Instant): Long {
        val member = if (memberId == null) "" else " AND member_id = :member_id"
        val statement = Statement.create(
            "SELECT CAST(COALESCE(SUM(cost_micros), 0) AS BIGINT) AS spent FROM usage_events " +
                "WHERE org_id = :org_id AND at >= :since$member",
        ).bind("org_id", orgId).bind("since", since)
        if (memberId != null) statement.bind("member_id", memberId)
        return driver.fetchInOrg(orgId, statement).getOrThrow().first().get("spent").asLong()
    }

    override suspend fun accountSpentMicros(orgId: Uuid, accountId: Uuid, since: Instant): Long = driver.fetchInOrg(
        orgId,
        Statement.create(
            "SELECT CAST(COALESCE(SUM(cost_micros), 0) AS BIGINT) AS spent FROM usage_events " +
                "WHERE org_id = :org_id AND account_id = :account_id AND at >= :since",
        ).bind("org_id", orgId).bind("account_id", accountId).bind("since", since),
    ).getOrThrow().first().get("spent").asLong()

    override suspend fun summary(window: UsageWindow, groupBy: UsageGroupBy, limit: Int): List<UsageTotal> {
        val member = if (window.memberId == null) "" else " AND member_id = :member_id"
        val until = if (window.until == null) "" else " AND at < :until"
        val models = if (groupBy == UsageGroupBy.MODEL) " AND kind = 'MODEL'" else ""
        val statement = Statement.create(
            "SELECT ${groupBy.keyExpression()} AS group_key, COUNT(*) AS events, " +
                "CAST(COALESCE(SUM(input_tokens), 0) AS BIGINT) AS input_tokens, " +
                "CAST(COALESCE(SUM(output_tokens), 0) AS BIGINT) AS output_tokens, " +
                "CAST(COALESCE(SUM(cache_read_tokens), 0) AS BIGINT) AS cache_read_tokens, " +
                "CAST(COALESCE(SUM(cache_write_tokens), 0) AS BIGINT) AS cache_write_tokens, " +
                "CAST(COALESCE(SUM(cost_micros), 0) AS BIGINT) AS total_cost " +
                "FROM usage_events WHERE org_id = :org_id AND at >= :since$until$member$models " +
                "GROUP BY 1 ORDER BY total_cost DESC, group_key NULLS LAST LIMIT :limit",
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
        val member = if (memberId == null) "" else " AND member_id = :member_id"
        val cursor = when {
            before == null -> ""
            before.id == null -> " AND at < :before_at"
            else -> " AND (at, id) < (:before_at, :before_id)"
        }
        val statement = Statement.create(
            "SELECT $COLUMNS FROM usage_events WHERE org_id = :org_id$member$cursor " +
                "ORDER BY at DESC, id DESC LIMIT :limit",
        ).bind("org_id", orgId).bind("limit", limit)
        if (memberId != null) statement.bind("member_id", memberId)
        if (before != null) {
            statement.bind("before_at", before.at)
            if (before.id != null) statement.bind("before_id", before.id)
        }
        return driver.fetchInOrg(orgId, statement).getOrThrow().map { it.toEvent() }
    }

    override suspend fun purge(before: Instant, limit: Int): Long = driver.transaction {
        // The one operation across orgs, opened by its own policies (UsageMigrations version 2): old events only.
        execute("SET LOCAL ROLE $TENANT_ROLE").getOrThrow()
        execute("SELECT set_config('$RETENTION_SETTING', 'on', true)").getOrThrow()
        execute(
            Statement.create(
                "DELETE FROM usage_events WHERE id IN (SELECT id FROM usage_events WHERE at < :before LIMIT :limit)",
            ).bind("before", before).bind("limit", limit),
        ).getOrThrow()
    }

    private fun UsageGroupBy.keyExpression(): String = when (this) {
        UsageGroupBy.MEMBER -> "CAST(member_id AS TEXT)"
        UsageGroupBy.KIND -> "kind"
        UsageGroupBy.MODEL -> "name"
        UsageGroupBy.PROVIDER -> "provider"
        UsageGroupBy.ACCOUNT -> "CAST(account_id AS TEXT)"
    }

    private fun Statement.bindEvent(prefix: String, event: UsageEvent): Statement = bind("${prefix}id", event.id)
        .bind("${prefix}org_id", event.orgId)
        .bindNullable("${prefix}member_id", event.memberId, Uuid::class)
        .bind("${prefix}kind", event.kind.name)
        .bind("${prefix}name", event.name)
        .bindNullable("${prefix}provider", event.provider, String::class)
        .bindNullable("${prefix}account_id", event.accountId, Uuid::class)
        .bindNullable("${prefix}session_id", event.sessionId, String::class)
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
        inputTokens = get("input_tokens").asLong(),
        outputTokens = get("output_tokens").asLong(),
        cacheReadTokens = get("cache_read_tokens").asLong(),
        cacheWriteTokens = get("cache_write_tokens").asLong(),
        costMicros = get("cost_micros").asLong(),
        durationMillis = get("duration_ms").asLong(),
        outcome = get("outcome").asString(),
        at = get("at").asInstant(),
    )

    private companion object {
        val PARAMETERS = listOf(
            "id", "org_id", "member_id", "kind", "name", "provider", "account_id", "session_id", "input_tokens",
            "output_tokens", "cache_read_tokens", "cache_write_tokens", "cost_micros", "duration_ms", "outcome", "at",
        )
        val COLUMNS = PARAMETERS.joinToString(", ")
    }
}
