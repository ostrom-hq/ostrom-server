// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.usage.domain

import run.ostrom.server.secrets.domain.Caller
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The bounds of what clients send and read. */
object UsageRules {
    /** Events per ingestion request. */
    const val MAX_EVENTS_PER_REQUEST: Int = 500

    /** Length of an event's name and session id. */
    const val MAX_NAME_LENGTH: Int = 200

    /** Length of an outcome key. */
    const val MAX_OUTCOME_LENGTH: Int = 64

    /** The longest duration a client may declare: a week, longer than any session. */
    const val MAX_DURATION_MILLIS: Long = 7L * 24 * 3600 * 1000

    /** How far in the future a client clock may be. */
    val MAX_CLOCK_SKEW: Duration = 5.minutes

    /** How old a client event may be: a CLI that was offline flushes later, not months later. */
    val MAX_EVENT_AGE: Duration = 7.days

    /** Events one member may record per minute: twelve full requests. */
    const val MAX_EVENTS_PER_MINUTE: Int = 6_000

    /** Days a summary may cover. */
    val SUMMARY_DAYS: IntRange = 1..90

    /** Groups a summary returns at most. */
    const val MAX_GROUPS: Int = 1000

    /** Events a page holds at most. */
    const val MAX_PAGE: Int = 200

    /** Kinds a client may declare: model usage, and its cost, only ever come from the gateway. */
    val CLIENT_KINDS: Set<UsageKind> = setOf(UsageKind.MCP, UsageKind.SKILL, UsageKind.HOOK, UsageKind.SESSION)
}

/**
 * An event as a client (the CLI) declares it: no org, no member, no tokens and no cost, which come from the server.
 *
 * @property kind One of [UsageRules.CLIENT_KINDS].
 * @property name The skill, hook event, `"server/tool"` or session id.
 * @property sessionId The agent session.
 * @property durationMillis How long it took.
 * @property outcome `ok` or a short error key.
 * @property at When it happened, or null for now.
 */
data class ClientUsageEvent(
    val kind: UsageKind,
    val name: String,
    val sessionId: String? = null,
    val durationMillis: Long = 0,
    val outcome: String = "ok",
    val at: Instant? = null,
)

/** Records the events a client declares. */
interface IngestUsageEventsUseCase {
    /**
     * Validates and records them, for the caller's org and member.
     *
     * @param caller The member whose agent sent them.
     * @param events The events.
     *
     * @return The number recorded.
     *
     * @throws TooManyUsageEventsException Over [UsageRules.MAX_EVENTS_PER_REQUEST].
     * @throws UsageRateLimitedException Over [UsageRules.MAX_EVENTS_PER_MINUTE] for this member.
     * @throws InvalidUsageEventException When one event is malformed or of a server-only kind; none is recorded.
     */
    suspend operator fun invoke(caller: Caller, events: List<ClientUsageEvent>): Int
}

/**
 * [IngestUsageEventsUseCase] on the ledger and the feed.
 *
 * @param repository The ledger.
 * @param feed The live feed.
 * @param clock The time.
 * @param maxMembers Members whose minute is counted at once; past it, finished minutes are forgotten, then all.
 */
class IngestUsageEventsUseCaseImpl(
    private val repository: UsageRepository,
    private val feed: UsageFeed,
    private val clock: Clock,
    private val maxMembers: Int = 10_000,
) : IngestUsageEventsUseCase {
    private class Minute(val start: Instant, var count: Int)

    private val lock = Mutex()
    private val minutes = HashMap<Pair<Uuid, Uuid>, Minute>()

    override suspend fun invoke(caller: Caller, events: List<ClientUsageEvent>): Int {
        if (events.size > UsageRules.MAX_EVENTS_PER_REQUEST) throw TooManyUsageEventsException()
        if (events.isEmpty()) return 0
        val now = clock.now()
        val validated = events.map { it.validated(now) }
        admit(caller, validated.size, now)
        val recorded = validated.map { it.toEvent(caller, now) }
        repository.insert(caller.orgId, recorded)
        recorded.forEach { feed.publish(it) }
        return recorded.size
    }

    private fun ClientUsageEvent.validated(now: Instant): ClientUsageEvent {
        val problem = when {
            kind !in UsageRules.CLIENT_KINDS -> "Model usage is recorded by the server only."
            name.isBlank() || name.length > UsageRules.MAX_NAME_LENGTH -> "The name is 1 to 200 characters."
            sessionId != null && (sessionId.isBlank() || sessionId.length > UsageRules.MAX_NAME_LENGTH) ->
                "The session id is 1 to 200 characters."
            durationMillis !in 0..UsageRules.MAX_DURATION_MILLIS -> "The duration is out of range."
            outcome.isBlank() || outcome.length > UsageRules.MAX_OUTCOME_LENGTH -> "The outcome is 1 to 64 characters."
            else -> timeProblem(now)
        }
        if (problem != null) throw InvalidUsageEventException(problem)
        return this
    }

    private fun ClientUsageEvent.timeProblem(now: Instant): String? {
        val at = at ?: return null
        return when {
            at > now + UsageRules.MAX_CLOCK_SKEW -> "The event is in the future."
            at < now - UsageRules.MAX_EVENT_AGE -> "The event is older than a week."
            else -> null
        }
    }

    /** Counts the events against the member's minute; the ledger and the live feed are not a free sink. */
    private suspend fun admit(caller: Caller, count: Int, now: Instant) = lock.withLock {
        val key = caller.orgId to caller.memberId
        var minute = minutes[key]
        if (minute == null || now - minute.start >= 1.minutes) {
            if (minutes.size >= maxMembers) {
                minutes.entries.removeAll { now - it.value.start >= 1.minutes }
                if (minutes.size >= maxMembers) minutes.clear()
            }
            minute = Minute(now, 0)
            minutes[key] = minute
        }
        if (minute.count + count > UsageRules.MAX_EVENTS_PER_MINUTE) throw UsageRateLimitedException()
        minute.count += count
    }

    private fun ClientUsageEvent.toEvent(caller: Caller, now: Instant) = UsageEvent(
        id = Uuid.random(),
        orgId = caller.orgId,
        memberId = caller.memberId,
        kind = kind,
        name = name,
        sessionId = sessionId,
        launchId = caller.launchId,
        durationMillis = durationMillis,
        outcome = outcome,
        at = at ?: now,
    )
}

/**
 * The totals of a period.
 *
 * @property since The start of the period.
 * @property groupBy The grouping.
 * @property groups The groups, most expensive first.
 */
data class UsageSummary(val since: Instant, val groupBy: UsageGroupBy, val groups: List<UsageTotal>) {
    /** The sum of every group. */
    val total: UsageTotal
        get() = UsageTotal(
            key = null,
            events = groups.sumOf { it.events },
            inputTokens = groups.sumOf { it.inputTokens },
            outputTokens = groups.sumOf { it.outputTokens },
            cacheReadTokens = groups.sumOf { it.cacheReadTokens },
            cacheWriteTokens = groups.sumOf { it.cacheWriteTokens },
            costMicros = groups.sumOf { it.costMicros },
        )
}

/** Totals the usage of the last days. */
interface UsageSummaryUseCase {
    /**
     * Totals it: an admin sees the whole org, a member only their own usage.
     *
     * @param caller The caller.
     * @param days The period, [UsageRules.SUMMARY_DAYS].
     * @param groupBy The grouping.
     *
     * @return The summary.
     *
     * @throws InvalidUsageQueryException When the period is out of range.
     */
    suspend operator fun invoke(caller: Caller, days: Int, groupBy: UsageGroupBy): UsageSummary
}

/**
 * [UsageSummaryUseCase] on the ledger.
 *
 * @param repository The ledger.
 * @param clock The time.
 */
class UsageSummaryUseCaseImpl(private val repository: UsageRepository, private val clock: Clock) : UsageSummaryUseCase {
    override suspend fun invoke(caller: Caller, days: Int, groupBy: UsageGroupBy): UsageSummary {
        if (days !in UsageRules.SUMMARY_DAYS) throw InvalidUsageQueryException("days is 1 to 90.")
        val since = clock.now() - days.days
        val groups = repository.summary(
            UsageWindow(caller.orgId, caller.visibleMember(), since),
            groupBy,
            UsageRules.MAX_GROUPS,
        )
        return UsageSummary(since, groupBy, groups)
    }
}

/** Lists the ledger, newest first. */
interface ListUsageEventsUseCase {
    /**
     * Lists a page: an admin sees the whole org, a member only their own events.
     *
     * @param caller The caller.
     * @param before Keyset cursor, or null for the newest.
     * @param limit The page size, brought into 1..[UsageRules.MAX_PAGE].
     *
     * @return The events.
     */
    suspend operator fun invoke(caller: Caller, before: UsageCursor?, limit: Int): List<UsageEvent>
}

/**
 * [ListUsageEventsUseCase] on the ledger.
 *
 * @param repository The ledger.
 */
class ListUsageEventsUseCaseImpl(private val repository: UsageRepository) : ListUsageEventsUseCase {
    override suspend fun invoke(caller: Caller, before: UsageCursor?, limit: Int): List<UsageEvent> =
        repository.list(caller.orgId, caller.visibleMember(), before, limit.coerceIn(1, UsageRules.MAX_PAGE))
}

/** Follows the org's new events live. */
interface WatchUsageUseCase {
    /**
     * Subscribes: an admin follows the whole org, a member only their own events.
     *
     * @param caller The caller.
     * @param onSubscribed Runs once the subscription is active, before the first event: nothing published after it
     *   is missed.
     *
     * @return The events, as long as the flow is collected.
     */
    suspend operator fun invoke(caller: Caller, onSubscribed: suspend () -> Unit = {}): Flow<UsageEvent>
}

/**
 * [WatchUsageUseCase] on the feed.
 *
 * @param feed The live feed.
 */
class WatchUsageUseCaseImpl(private val feed: UsageFeed) : WatchUsageUseCase {
    override suspend fun invoke(caller: Caller, onSubscribed: suspend () -> Unit): Flow<UsageEvent> {
        val events = feed.subscribe(caller.orgId).onSubscription { onSubscribed() }
        val member = caller.visibleMember() ?: return events
        return events.filter { it.memberId == member }
    }
}

/** Deletes the events past the retention. */
interface PurgeUsageUseCase {
    /**
     * Deletes them, in batches.
     *
     * @return The number of events deleted.
     */
    suspend operator fun invoke(): Long
}

/**
 * [PurgeUsageUseCase] on the ledger.
 *
 * @param repository The ledger.
 * @param clock The time.
 * @param retention How long events are kept.
 * @param batchSize Events deleted per statement.
 * @param maxBatches Statements per run; the rest waits for the next run.
 */
class PurgeUsageUseCaseImpl(
    private val repository: UsageRepository,
    private val clock: Clock,
    private val retention: Duration,
    private val batchSize: Int = 10_000,
    private val maxBatches: Int = 100,
) : PurgeUsageUseCase {
    override suspend fun invoke(): Long {
        val before = clock.now() - retention
        var total = 0L
        repeat(maxBatches) {
            val deleted = repository.purge(before, batchSize)
            total += deleted
            if (deleted < batchSize) return total
        }
        return total
    }
}

/** The member a caller may see: everyone (null) for an admin, themselves for a member. */
private fun Caller.visibleMember(): Uuid? = if (role.isAdmin) null else memberId
