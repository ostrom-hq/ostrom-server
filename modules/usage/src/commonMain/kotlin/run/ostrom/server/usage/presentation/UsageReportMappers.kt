// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.usage.presentation

import run.ostrom.server.usage.domain.InvalidUsageQueryException
import run.ostrom.server.usage.domain.UsageReport
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** A `from` or `to` query parameter: an ISO 8601 instant, or a date read as its midnight UTC. */
fun String.toInstantParameter(name: String): Instant = Instant.parseOrNull(this)
    ?: Instant.parseOrNull("${this}T00:00:00Z")
    ?: throw InvalidUsageQueryException("$name is an ISO 8601 time or date.")

/** The `member` query parameter. */
fun String.toMemberParameter(): Uuid =
    Uuid.parseOrNull(this) ?: throw InvalidUsageQueryException("member is a member id.")

/** Events of no member, in `byMember`. */
private const val UNATTRIBUTED = "unattributed"

private const val MICROS_PER_USD = 1_000_000.0

/** Maps a report to the API (contract `Usage`). */
fun UsageReport.toResponse() = UsageResponse(
    from = from.toString(),
    to = to.toString(),
    usd = total.costMicros / MICROS_PER_USD,
    tokensIn = total.inputTokens,
    tokensOut = total.outputTokens,
    byMember = byMember.associate { (it.key ?: UNATTRIBUTED) to it.costMicros / MICROS_PER_USD },
    // A model call always names its model; a row without one would still be reported, under "".
    byModel = byModel.associate { it.key.orEmpty() to it.costMicros / MICROS_PER_USD },
    cacheReadTokens = total.cacheReadTokens,
    cacheWriteTokens = total.cacheWriteTokens,
    events = total.events,
)
