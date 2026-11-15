// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.reports.infrastructure

import run.ostrom.server.kernel.database.present
import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.QueryAlias
import org.jetbrains.exposed.v1.core.QueryParameter
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.anyFrom
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.unionAll
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * One line of the spending report as it is added up, before it is shown: `dim` is `agent`, `repo`, `person` or `day`;
 * an empty key or label is no session (or no member).
 *
 * @property dim The way the ledger is grouped.
 * @property key The group.
 * @property label Its name: a person's login, else the key; a day's is empty.
 * @property micros What it spent.
 * @property sessions The sessions its spending was found in; a day's is 0.
 */
internal data class SpendingRow(
    val dim: String,
    val key: String,
    val label: String,
    val micros: Long,
    val sessions: Long,
) {
    override fun toString(): String = "$dim|$key|$label|$micros|$sessions"
}

/**
 * The reads of the spending report, over a window cut in three ([SpendingRanges]).
 *
 * An event names its session by its id, or, for a model call through the gateway, by its launch (`launch_id`; its
 * `session_id` is then the launch's id too): the agent and the repository come from the session with that id, else
 * from the first segment of that launch, one session only so that nothing counts twice. A line's `sessions` counts the
 * sessions its spending was found in: a launch counts once, a call outside any session never.
 *
 * The whole days of the window come from the daily totals the usage module keeps with the ledger (`usage_daily`); only
 * the hours before the first whole day and after the last are added up from the calls, so the result is the one of
 * the calls alone (ReportsDailyTotalsPostgresTest), and a month reads a few thousand totals instead of every call
 * (ReportsReadsBenchTest). The ledger is added up by member, session, launch and day ([calls]) and joined to the
 * session of that id and to the member's login, in one read; the first segment of each launch whose calls named no
 * session is read next ([launches]), only for those launches. The four ways are then added up here: Exposed has no
 * `GROUPING SETS`, and the joined lines are a few per session and day.
 *
 * @param orgId The organization.
 * @param memberId One member's spending only, when set.
 * @param ranges The window.
 */
internal class SpendingReads(
    private val orgId: Uuid,
    private val memberId: Uuid?,
    private val ranges: SpendingRanges,
) {
    private val daily = ReportsUsageDaily
    private val events = ReportsUsageEvents
    private val sessions = ReportsSessions
    private val members = ReportsMembers
    private val users = ReportsUsers
    // Built in the transaction: a union asks the dialect.
    private val ledger: QueryAlias by lazy {
        days().unionAll(hours(ranges.head)).unionAll(hours(ranges.tail)).alias("c")
    }

    // The whole days, from their totals.
    private fun days(): Query {
        val (from, to) = ranges.days
        return daily.select(daily.memberId, daily.sessionId, daily.launchId, daily.day, daily.costMicros).where {
            val window = (daily.orgId eq orgId) and
                (daily.day greaterEq QueryParameter(from, DateColumnType())) and
                (daily.day less QueryParameter(to, DateColumnType()))
            if (memberId == null) window else window and (daily.memberId eq memberId)
        }
    }

    // The hours of one part of the window, from the calls, by day.
    private fun hours(range: Pair<Instant, Instant>): Query {
        val day = utcDay(events.at)
        return events.select(events.memberId, events.sessionId, events.launchId, day, events.costMicros.sum())
            .where {
                val window = (events.orgId eq orgId) and (events.at greaterEq range.first) and
                    (events.at less range.second) and (events.costMicros greater 0L)
                if (memberId == null) window else window and (events.memberId eq memberId)
            }
            .groupBy(events.memberId, events.sessionId, events.launchId, day)
    }

    /** The ledger of the window by member, session, launch and day, with the session of that id and the login. */
    fun calls(): Query = ledger
        .join(sessions, JoinType.LEFT, additionalConstraint = {
            (sessions.orgId eq orgId) and (sessions.id eq ledger[daily.sessionId])
        })
        .join(members, JoinType.LEFT, additionalConstraint = {
            (members.orgId eq orgId) and (members.id eq ledger[daily.memberId])
        })
        .join(users, JoinType.LEFT, additionalConstraint = { users.id eq members.userId })
        .select(
            ledger[daily.memberId], ledger[daily.launchId], ledger[daily.day], ledger[daily.costMicros],
            sessions.id, sessions.agent, sessions.repo, users.login,
        )

    /**
     * The first segment of each of [launchIds], by their order; of several sessions of a launch at the same segment,
     * the first by id, so that every read of the report gives the same one.
     *
     * @param launchIds The launches.
     * @return The read.
     */
    fun launches(launchIds: List<String>): Query = sessions
        .select(sessions.launchId, sessions.id, sessions.agent, sessions.repo)
        .withDistinctOn(sessions.launchId)
        .where { (sessions.orgId eq orgId) and (sessions.launchId eq anyFrom(launchIds, TextColumnType())) }
        .orderBy(sessions.launchId to SortOrder.ASC, sessions.segment to SortOrder.ASC, sessions.id to SortOrder.ASC)

    /**
     * Reads the window and adds it up four ways, in this transaction.
     *
     * @return The lines, in no order.
     */
    fun JdbcTransaction.rows(): List<SpendingRow> {
        val joined = calls().map { row ->
            Call(
                memberId = row[ledger[daily.memberId]],
                launchId = row[ledger[daily.launchId]],
                day = row[ledger[daily.day]],
                micros = row[ledger[daily.costMicros]],
                session = row[sessions.id]?.let { Spot(it, row[sessions.agent], row[sessions.repo]) },
                login = row[users.login],
            )
        }
        val unfound = joined.filter { it.session == null }.mapNotNull { it.launchId }.distinct()
        val firstSegments = if (unfound.isEmpty()) {
            emptyMap()
        } else {
            // Read by those launches only: each row has one.
            launches(unfound).associate {
                val spot = Spot(it[sessions.id], it[sessions.agent], it[sessions.repo])
                it.present(sessions.launchId, sessions.id) to spot
            }
        }
        val spent = joined.map { call -> call to (call.session ?: call.launchId?.let { firstSegments[it] }) }
        fun lines(dim: String, key: (Call, Spot?) -> String?, label: (List<Pair<Call, Spot?>>, String) -> String) =
            // Grouped on the value itself, none apart from an empty one, as SQL groups: both are shown empty.
            spent.groupBy { (call, spot) -> key(call, spot) }.map { (group, lines) ->
                val found = lines.mapNotNull { it.second?.id }.distinct().size.toLong()
                SpendingRow(dim, group.orEmpty(), label(lines, group.orEmpty()), lines.sumOf { it.first.micros }, found)
            }
        val byKey: (List<Pair<Call, Spot?>>, String) -> String = { _, key -> key }
        return lines(AGENT, { _, spot -> spot?.agent }, byKey) +
            lines(REPO, { _, spot -> spot?.repo }, byKey) +
            lines(PERSON, { call, _ -> call.memberId?.toString() }) { lines, key ->
                lines.mapNotNull { it.first.login }.minOrNull() ?: key
            } +
            joined.groupBy { it.day }.map { (day, calls) ->
                SpendingRow(DAY, day.toString(), "", calls.sumOf { it.micros }, 0)
            }
    }

    // A line of the ledger joined to its session and its member's login.
    private class Call(
        val memberId: Uuid?,
        val launchId: String?,
        val day: LocalDate,
        val micros: Long,
        val session: Spot?,
        val login: String?,
    )

    // Where spending was found: a session's id, agent and repository.
    private class Spot(val id: String, val agent: String, val repo: String?)

    /** The ways the lines are added up. */
    companion object {
        /** By agent. */
        const val AGENT = "agent"

        /** By repository. */
        const val REPO = "repo"

        /** By person. */
        const val PERSON = "person"

        /** By day. */
        const val DAY = "day"
    }
}
