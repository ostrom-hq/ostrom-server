// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.usage.infrastructure

import run.ostrom.server.kernel.database.InstantColumnType
import run.ostrom.server.kernel.database.Read
import run.ostrom.server.kernel.database.asBigint
import run.ostrom.server.kernel.database.rowLess
import run.ostrom.server.usage.domain.UsageCursor
import run.ostrom.server.usage.domain.UsageEvent
import run.ostrom.server.usage.domain.UsageGroupBy
import run.ostrom.server.usage.domain.UsageKind
import run.ostrom.server.usage.domain.UsageTotal
import run.ostrom.server.usage.domain.UsageWindow
import org.jetbrains.exposed.v1.core.Cast
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryParameter
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.UuidColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.andIfNotNull
import org.jetbrains.exposed.v1.core.coalesce
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.longLiteral
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.uuid.Uuid

// The ledger's reads of events and totals (UsageRepositoryImpl, UsageFeedReadsImpl), one function each so that the plan
// tests explain exactly what the repository sends. Every one filters on the org.

/** The columns of an event, as [toEvent] reads them. */
private val EVENT_COLUMNS: List<Column<*>> = UsageEvents.columns

/**
 * The totals of [window] by [groupBy], most expensive first, then by key (no key last), [limit] groups at most;
 * [UsageGroupBy.MODEL] only counts model events.
 */
internal fun summaryRead(window: UsageWindow, groupBy: UsageGroupBy, limit: Int): Read<UsageTotal> {
    val e = UsageEvents
    val key = groupBy.key()
    val events = e.id.count()
    val input = totalOf(e.inputTokens)
    val output = totalOf(e.outputTokens)
    val cacheRead = totalOf(e.cacheReadTokens)
    val cacheWrite = totalOf(e.cacheWriteTokens)
    val cost = totalOf(e.costMicros)
    val query = e.select(key, events, input, output, cacheRead, cacheWrite, cost)
        .where {
            (e.orgId eq window.orgId) and (e.at greaterEq window.since) andIfNotNull
                window.until?.let { e.at less it } andIfNotNull
                window.memberId?.let { e.memberId eq it } andIfNotNull
                (if (groupBy == UsageGroupBy.MODEL) e.kind eq UsageKind.MODEL.name else null)
        }
        .groupBy(key)
        .orderBy(cost to SortOrder.DESC, key to SortOrder.ASC_NULLS_LAST)
        .limit(limit)
    return Read(query) {
        UsageTotal(
            key = it[key],
            events = it[events],
            inputTokens = it[input],
            outputTokens = it[output],
            cacheReadTokens = it[cacheRead],
            cacheWriteTokens = it[cacheWrite],
            costMicros = it[cost],
        )
    }
}

/** The total of [column] in a group, 0 for none. */
private fun totalOf(column: Column<Long>): ExpressionWithColumnType<Long> =
    coalesce(column.sum(), longLiteral(0)).asBigint()

/** The key a summary groups by, as text. */
@Suppress("UNCHECKED_CAST") // A TEXT column read as a key that may be null: the same values.
private fun UsageGroupBy.key(): ExpressionWithColumnType<String?> = when (this) {
    UsageGroupBy.MEMBER -> Cast<String?>(UsageEvents.memberId, TextColumnType())
    UsageGroupBy.KIND -> UsageEvents.kind as ExpressionWithColumnType<String?>
    UsageGroupBy.MODEL -> UsageEvents.name as ExpressionWithColumnType<String?>
    UsageGroupBy.PROVIDER -> UsageEvents.provider
    UsageGroupBy.ACCOUNT -> Cast<String?>(UsageEvents.accountId, TextColumnType())
}

/**
 * A page of the org's events (or [memberId]'s), newest first, strictly before [before]: its instant then its id, the
 * row comparison `(at, id) < (?, ?)` that walks the index on `(org_id, at, id)`; everything before the instant for a
 * cursor without id.
 */
internal fun listRead(orgId: Uuid, memberId: Uuid?, before: UsageCursor?, limit: Int): Read<UsageEvent> {
    val e = UsageEvents
    val query = e.select(EVENT_COLUMNS)
        .where {
            (e.orgId eq orgId) andIfNotNull memberId?.let { e.memberId eq it } andIfNotNull before?.let(::olderThan)
        }
        .orderBy(e.at to SortOrder.DESC, e.id to SortOrder.DESC)
        .limit(limit)
    return Read(query) { it.toEvent() }
}

/** The events strictly before [cursor]. */
private fun olderThan(cursor: UsageCursor): Op<Boolean> {
    val e = UsageEvents
    val id = cursor.id
    if (id == null) return e.at less cursor.at
    return rowLess(
        listOf(e.at, e.id),
        listOf(QueryParameter(cursor.at, InstantColumnType()), QueryParameter(id, UuidColumnType())),
    )
}

/** The events of the org among [ids], by their primary key (the feed of another replica). */
internal fun feedRead(orgId: Uuid, ids: List<Uuid>): Query {
    val e = UsageEvents
    return e.select(EVENT_COLUMNS).where { (e.orgId eq orgId) and (e.id inList ids) }
}

/** The event of a row of [EVENT_COLUMNS]. */
internal fun ResultRow.toEvent(): UsageEvent {
    val e = UsageEvents
    return UsageEvent(
        id = this[e.id],
        orgId = this[e.orgId],
        memberId = this[e.memberId],
        kind = UsageKind.valueOf(this[e.kind]),
        name = this[e.name],
        provider = this[e.provider],
        accountId = this[e.accountId],
        sessionId = this[e.sessionId],
        launchId = this[e.launchId],
        workflowId = this[e.workflowId],
        inputTokens = this[e.inputTokens],
        outputTokens = this[e.outputTokens],
        cacheReadTokens = this[e.cacheReadTokens],
        cacheWriteTokens = this[e.cacheWriteTokens],
        costMicros = this[e.costMicros],
        durationMillis = this[e.durationMs],
        outcome = this[e.outcome],
        at = this[e.at],
    )
}
