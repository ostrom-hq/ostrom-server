// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.reports.infrastructure

import dev.ostrom.server.kernel.database.withOrganization
import dev.ostrom.server.reports.domain.ActivityCursor
import dev.ostrom.server.reports.domain.ActivityItem
import dev.ostrom.server.reports.domain.ActivityKind
import dev.ostrom.server.reports.domain.ReportsRepository
import dev.ostrom.server.reports.domain.SpendingDay
import dev.ostrom.server.reports.domain.SpendingLine
import dev.ostrom.server.reports.domain.SpendingTotals
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import kotlinx.datetime.LocalDate
import kotlin.math.roundToLong
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [ReportsRepository] on PostgreSQL. Each read is one statement in a transaction scoped to the organization
 * ([withOrganization]): the row level security of the tables it reads applies, on top of their `org_id` filters.
 *
 * @param driver The database.
 */
class ReportsRepositoryImpl(private val driver: Driver) : ReportsRepository {
    override suspend fun spending(orgId: Uuid, memberId: Uuid?, from: Instant, to: Instant): SpendingTotals {
        val statement = spendingStatement(orgId, memberId, from, to)
        val rows = driver.withOrganization(orgId) { it.fetchAll(statement).getOrThrow().map { row -> row } }
        fun lines(dim: String) = rows.filter { it.get("dim").asString() == dim }.map { it.toLine(dim) }
        return SpendingTotals(
            byAgent = lines(AGENT),
            byRepo = lines(REPO),
            byPerson = lines(PERSON),
            byDay = rows.filter { it.get("dim").asString() == DAY }.map {
                SpendingDay(LocalDate.parse(it.get("group_key").asString()), it.get("micros").asLong())
            }.sortedBy { it.date },
        )
    }

    override suspend fun activity(
        orgId: Uuid,
        before: ActivityCursor?,
        repo: String?,
        limit: Int,
    ): List<ActivityItem> {
        val statement = Statement.create(ReportsQueries.activity(cursor = before != null, repo = repo != null))
            .bind("org_id", orgId).bind("limit", limit)
        before?.let { statement.bind("before_at", it.at).bind("before_id", it.id) }
        repo?.let { statement.bind("repo", it) }
        return driver.withOrganization(orgId) { tx -> tx.fetchAll(statement).getOrThrow().map { it.toItem() } }
    }

    private companion object {
        const val AGENT = "agent"
        const val REPO = "repo"
        const val PERSON = "person"
        const val DAY = "day"
        const val MICROS_PER_USD = 1_000_000.0
        val KINDS = ActivityKind.entries.associateBy { it.wire }

        fun ResultSet.Row.toLine(dim: String): SpendingLine {
            val key = get("group_key").asString()
            val label = get("label").asString().ifEmpty { if (dim == PERSON) "Organization" else "No session" }
            return SpendingLine(key, label, get("micros").asLong(), get("sessions").asLong())
        }

        fun ResultSet.Row.toItem(): ActivityItem {
            val kind = get("kind").asString()
            return ActivityItem(
                id = get("item_id").asString(),
                at = get("at").asInstant(),
                kind = KINDS.getValue(kind),
                actor = get("actor").asString(),
                subject = get("subject").asString(),
                outcome = get("outcome").asStringOrNull(),
                repo = get("repo").asStringOrNull(),
                costMicros = get("cost_usd").asStringOrNull()?.let { (it.toDouble() * MICROS_PER_USD).roundToLong() },
                sessionId = get("session_id").asStringOrNull(),
                gateId = get("gate_id").asStringOrNull(),
            )
        }
    }
}
