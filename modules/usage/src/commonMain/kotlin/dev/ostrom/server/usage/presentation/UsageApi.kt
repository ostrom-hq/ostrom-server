// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.usage.presentation

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

/**
 * `/api/v1/usage`: the ledger of the caller's org (from the token). The query of `/api/v1/usage` itself is
 * ostrom-core's `getUsage`; `/summary`, `/events` and `/live` are additions. An admin reads the whole org, a member
 * only their own usage.
 *
 * @property from The start: an ISO 8601 instant or date (UTC); 30 days before `to` when absent.
 * @property to The end, excluded; now when absent.
 * @property member For an admin, one member only.
 */
@Serializable
@Resource("/api/v1/usage")
class UsageApi(val from: String? = null, val to: String? = null, val member: String? = null) {
    /**
     * `/summary`: totals of the last days per group.
     *
     * @property parent The ledger.
     * @property days The period, 1 to 90 days.
     * @property groupBy `member`, `kind`, `model`, `provider` or `account`.
     */
    @Serializable
    @Resource("summary")
    class Summary(val parent: UsageApi = UsageApi(), val days: Int = 7, val groupBy: String = "member")

    /**
     * `/events`: GET pages the events, newest first; POST records the client's events (skills, hooks, sessions, MCP).
     *
     * @property parent The ledger.
     * @property before Keyset cursor: the `next` of the previous page, or an ISO 8601 time.
     * @property limit Page size, 1 to 200.
     */
    @Serializable
    @Resource("events")
    class Events(val parent: UsageApi = UsageApi(), val before: String? = null, val limit: Int = 50)

    /**
     * `/live`: server-sent events of the org's new events (the live spending map).
     *
     * @property parent The ledger.
     */
    @Serializable
    @Resource("live")
    class Live(val parent: UsageApi = UsageApi())
}

/** Scopes of a console token on the usage routes (a browser session has every user scope). */
object UsageScopes {
    /** Reads the ledger. */
    const val READ: String = "usage:read"

    /** Records client events. */
    const val WRITE: String = "usage:write"
}

/**
 * Spending over a period (contract `Usage`, with additions).
 *
 * @property from The start (ISO 8601).
 * @property to The end, excluded (ISO 8601).
 * @property usd The cost in US dollars.
 * @property tokensIn Input tokens, cache reads and writes excluded.
 * @property tokensOut Output tokens.
 * @property byMember US dollars per member id (`unattributed` for events of no member).
 * @property byModel US dollars per model, model calls only.
 * @property cacheReadTokens Cached input tokens read (addition).
 * @property cacheWriteTokens Input tokens written to the cache (addition).
 * @property events The number of events (addition).
 */
@Serializable
data class UsageResponse(
    val from: String,
    val to: String,
    val usd: Double,
    val tokensIn: Long,
    val tokensOut: Long,
    val byMember: Map<String, Double>,
    val byModel: Map<String, Double>,
    val cacheReadTokens: Long,
    val cacheWriteTokens: Long,
    val events: Long,
)

/**
 * Events declared by a client.
 *
 * @property events At most 500.
 */
@Serializable
data class UsageEventsRequest(val events: List<UsageEventRequest>)

/**
 * One declared event: the org, the member and the cost always come from the server.
 *
 * @property kind `skill`, `hook`, `session` or `mcp`.
 * @property name The skill, hook event, session id or `"server/tool"`.
 * @property sessionId The agent session.
 * @property durationMillis How long it took.
 * @property outcome `ok` or a short error key.
 * @property at When it happened (ISO 8601); now when absent.
 */
@Serializable
data class UsageEventRequest(
    val kind: String,
    val name: String,
    val sessionId: String? = null,
    val durationMillis: Long = 0,
    val outcome: String = "ok",
    val at: String? = null,
)

/**
 * What the ingestion recorded.
 *
 * @property accepted The number of events.
 */
@Serializable
data class UsageAcceptedResponse(val accepted: Int)

/**
 * The totals of a group.
 *
 * @property key The member id, kind, model, provider or account id; absent for the total and for events without one.
 * @property events Events.
 * @property inputTokens Input tokens.
 * @property outputTokens Output tokens.
 * @property cacheReadTokens Cache read tokens.
 * @property cacheWriteTokens Cache write tokens.
 * @property costMicros Cost, USD millionths.
 */
@Serializable
data class UsageTotalResponse(
    val key: String? = null,
    val events: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val cacheWriteTokens: Long,
    val costMicros: Long,
)

/**
 * A summary.
 *
 * @property since Start of the period (ISO 8601).
 * @property groupBy The grouping.
 * @property groups The groups, most expensive first.
 * @property total The sum of the groups.
 */
@Serializable
data class UsageSummaryResponse(
    val since: String,
    val groupBy: String,
    val groups: List<UsageTotalResponse>,
    val total: UsageTotalResponse,
)

/**
 * An event as clients see it.
 *
 * @property id The event.
 * @property memberId The member, absent for org-level work.
 * @property kind `model`, `mcp`, `skill`, `hook` or `session`.
 * @property name What was used.
 * @property provider The provider.
 * @property accountId The pooled account.
 * @property sessionId The session.
 * @property launchId The launch whose agent token sent it; absent for a person's own credential.
 * @property inputTokens Input tokens.
 * @property outputTokens Output tokens.
 * @property cacheReadTokens Cache read tokens.
 * @property cacheWriteTokens Cache write tokens.
 * @property costMicros Cost, USD millionths.
 * @property durationMillis Duration.
 * @property outcome Outcome.
 * @property at When (ISO 8601).
 */
@Serializable
data class UsageEventResponse(
    val id: String,
    val memberId: String? = null,
    val kind: String,
    val name: String,
    val provider: String? = null,
    val accountId: String? = null,
    val sessionId: String? = null,
    val launchId: String? = null,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val cacheWriteTokens: Long,
    val costMicros: Long,
    val durationMillis: Long,
    val outcome: String,
    val at: String,
)

/**
 * A page of events.
 *
 * @property events The events, newest first.
 * @property next The cursor of the next page, or absent on the last one.
 */
@Serializable
data class UsageEventsPageResponse(val events: List<UsageEventResponse>, val next: String? = null)
