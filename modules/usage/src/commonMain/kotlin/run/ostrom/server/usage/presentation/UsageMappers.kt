// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.usage.presentation

import run.ostrom.server.kernel.database.storableInstantOrNull
import run.ostrom.server.usage.domain.ClientUsageEvent
import run.ostrom.server.usage.domain.InvalidUsageEventException
import run.ostrom.server.usage.domain.InvalidUsageQueryException
import run.ostrom.server.usage.domain.UsageCursor
import run.ostrom.server.usage.domain.UsageEvent
import run.ostrom.server.usage.domain.UsageGroupBy
import run.ostrom.server.usage.domain.UsageKind
import run.ostrom.server.usage.domain.UsageSummary
import run.ostrom.server.usage.domain.UsageTotal
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Separates the time and the id in a page cursor (`2026-10-30T10:00:00Z~<uuid>`). */
private const val CURSOR_SEPARATOR = '~'

/**
 * Maps a declared event to the domain.
 *
 * @throws InvalidUsageEventException When the kind or the time is not understood.
 */
fun UsageEventRequest.toClientEvent() = ClientUsageEvent(
    kind = UsageKind.entries.firstOrNull { it.name.equals(kind, ignoreCase = true) }
        ?: throw InvalidUsageEventException("Unknown kind."),
    name = name,
    sessionId = sessionId,
    durationMillis = durationMillis,
    outcome = outcome,
    at = at?.let { Instant.parseOrNull(it) ?: throw InvalidUsageEventException("at is an ISO 8601 time.") },
)

/**
 * Parses the `groupBy` parameter.
 *
 * @throws InvalidUsageQueryException When it names no grouping.
 */
fun String.toGroupBy(): UsageGroupBy = UsageGroupBy.entries.firstOrNull { it.name.equals(this, ignoreCase = true) }
    ?: throw InvalidUsageQueryException("groupBy is member, kind, model, provider or account.")

/**
 * Parses a page cursor: the `next` of a page, or a bare ISO 8601 time.
 *
 * @throws InvalidUsageQueryException When it is neither.
 */
fun String.toUsageCursor(): UsageCursor {
    val separator = indexOf(CURSOR_SEPARATOR)
    val time = if (separator < 0) this else substring(0, separator)
    val at = storableInstantOrNull(time) ?: throw invalidCursor()
    if (separator < 0) return UsageCursor(at, null)
    val id = Uuid.parseOrNull(substring(separator + 1)) ?: throw invalidCursor()
    return UsageCursor(at, id)
}

private fun invalidCursor() = InvalidUsageQueryException("before is not a valid cursor.")

/** The cursor of the page after this event. */
fun UsageEvent.toCursor(): String = "$at$CURSOR_SEPARATOR$id"

/** Maps an event to the API. */
fun UsageEvent.toResponse() = UsageEventResponse(
    id = id.toString(),
    memberId = memberId?.toString(),
    kind = kind.name.lowercase(),
    name = name,
    provider = provider,
    accountId = accountId?.toString(),
    sessionId = sessionId,
    launchId = launchId,
    inputTokens = inputTokens,
    outputTokens = outputTokens,
    cacheReadTokens = cacheReadTokens,
    cacheWriteTokens = cacheWriteTokens,
    costMicros = costMicros,
    durationMillis = durationMillis,
    outcome = outcome,
    at = at.toString(),
)

/** Maps a group's totals to the API. */
fun UsageTotal.toResponse() = UsageTotalResponse(
    key = key,
    events = events,
    inputTokens = inputTokens,
    outputTokens = outputTokens,
    cacheReadTokens = cacheReadTokens,
    cacheWriteTokens = cacheWriteTokens,
    costMicros = costMicros,
)

/** Maps a summary to the API. */
fun UsageSummary.toResponse() = UsageSummaryResponse(
    since = since.toString(),
    groupBy = groupBy.name.lowercase(),
    groups = groups.map { it.toResponse() },
    total = total.toResponse(),
)
