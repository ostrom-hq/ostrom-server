// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.reports.presentation

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

/**
 * `GET /api/v1/spending?period=week|month` (ostrom-core's `getSpending`).
 *
 * @property period The period, the month when absent.
 */
@Serializable
@Resource("/api/v1/spending")
class SpendingApi(val period: String? = null)

/**
 * `GET /api/v1/activity?before=&repo=` (ostrom-core's `listActivity`).
 *
 * @property before The `next` of the previous page.
 * @property repo Only what concerns this repository.
 */
@Serializable
@Resource("/api/v1/activity")
class ActivityApi(val before: String? = null, val repo: String? = null)

/**
 * The contract's `SpendingLine`.
 *
 * @property key The agent, repository or member id.
 * @property label What to show.
 * @property usd The cost, US dollars.
 * @property sessions The sessions that spent it.
 */
@Serializable
data class SpendingLineDto(val key: String, val label: String, val usd: Double, val sessions: Long)

/**
 * The contract's `SpendingDay`.
 *
 * @property date The UTC day, `YYYY-MM-DD`.
 * @property usd The cost, US dollars.
 */
@Serializable
data class SpendingDayDto(val date: String, val usd: Double)

/**
 * The contract's `Spending`.
 *
 * @property period `week` or `month`.
 * @property from The start (ISO 8601), included.
 * @property to The end (ISO 8601): now.
 * @property totalUsd What was spent.
 * @property budgetUsd The monthly budget that applies to the reader, on the month only.
 * @property byAgent By agent.
 * @property byRepo By repository.
 * @property byPerson By person.
 * @property daily Every day of the period, zero included.
 * @property exceeded The budgets the reader sees spent past their limit today or this month (empty: none).
 */
@Serializable
data class SpendingDto(
    val period: String,
    val from: String,
    val to: String,
    val totalUsd: Double,
    val budgetUsd: Double? = null,
    val byAgent: List<SpendingLineDto>,
    val byRepo: List<SpendingLineDto>,
    val byPerson: List<SpendingLineDto>,
    val daily: List<SpendingDayDto>,
    val exceeded: List<ExceededBudgetDto> = emptyList(),
)

/**
 * The contract's `ExceededBudget`: a budget spent past its limit in its current day or month.
 *
 * @property scope `organization`, `member` or `workflow`.
 * @property subject For `member`: the member's id; for `workflow`: the workflow's id.
 * @property period `day` or `month`.
 * @property usd The limit, US dollars.
 * @property hardStop Whether it stops the calls; false, it only warns and the calls go on.
 * @property since When the server saw it reached (ISO 8601).
 */
@Serializable
data class ExceededBudgetDto(
    val scope: String,
    val subject: String? = null,
    val period: String,
    val usd: Double,
    val hardStop: Boolean,
    val since: String,
)

/**
 * The contract's `ActivityActor`.
 *
 * @property kind `person`.
 * @property name The login.
 */
@Serializable
data class ActivityActorDto(val kind: String, val name: String)

/**
 * The contract's `ActivityItem`.
 *
 * @property id The item.
 * @property at When (ISO 8601).
 * @property kind What happened.
 * @property actor Who.
 * @property summary One line for people.
 * @property repo The repository.
 * @property costUsd What an ended session cost.
 * @property gateId The gate.
 * @property sessionId The session.
 */
@Serializable
data class ActivityItemDto(
    val id: String,
    val at: String,
    val kind: String,
    val actor: ActivityActorDto,
    val summary: String,
    val repo: String? = null,
    val costUsd: Double? = null,
    val gateId: String? = null,
    val sessionId: String? = null,
)

/**
 * A page of the feed.
 *
 * @property items The items, newest first.
 * @property next The `before` of the next page, absent on the last one.
 */
@Serializable
data class ActivityPageDto(val items: List<ActivityItemDto>, val next: String? = null)
