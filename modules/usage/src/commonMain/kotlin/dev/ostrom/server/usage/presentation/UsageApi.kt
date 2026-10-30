// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.usage.presentation

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

/** `POST /v1/usage/events`: the CLI declares its members' skills, hooks, sessions and MCP calls. */
@Serializable
@Resource("/v1/usage/events")
class UsageIngestApi

/**
 * `/v1/orgs/{orgId}/usage`: the org's ledger. To move to ostrom-core with the API contract.
 *
 * @property orgId The org.
 */
@Serializable
@Resource("/v1/orgs/{orgId}/usage")
class OrgUsageApi(val orgId: String) {
    /**
     * `/summary`: totals of the last days per group.
     *
     * @property parent The ledger.
     * @property days The period, 1 to 90 days.
     * @property groupBy `member`, `kind`, `model`, `provider` or `account`.
     */
    @Serializable
    @Resource("summary")
    class Summary(val parent: OrgUsageApi, val days: Int = 7, val groupBy: String = "member")

    /**
     * `/events`: the events, newest first.
     *
     * @property parent The ledger.
     * @property before Keyset cursor: the `next` of the previous page, or an ISO 8601 time.
     * @property limit Page size, 1 to 200.
     */
    @Serializable
    @Resource("events")
    class Events(val parent: OrgUsageApi, val before: String? = null, val limit: Int = 50)

    /**
     * `/live`: server-sent events of the org's new events.
     *
     * @property parent The ledger.
     */
    @Serializable
    @Resource("live")
    class Live(val parent: OrgUsageApi)
}

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
