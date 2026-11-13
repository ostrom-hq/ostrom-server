// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.billing.domain

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

// A failed Stripe task is retried after 30 s, then twice as late each time, at most an hour later.
private val FIRST_RETRY = 30.seconds
private val LAST_RETRY = 60.minutes
private const val MAX_DOUBLINGS = 7

// Longer than a pass of the worker on a healthy Stripe; a task taken again after it is sent once more, harmlessly.
private val OUTBOX_LEASE = 10.minutes

/** Records that a member launched an agent or answered a gate: they are an active person this period. */
interface RecordMemberActivityUseCase {
    /** Never fails because of Stripe: what Stripe must know is queued. */
    suspend operator fun invoke(orgId: Uuid, userId: String, at: Instant)
}

/** [RecordMemberActivityUseCase]. */
class RecordMemberActivityUseCaseImpl(
    private val repository: BillingRepository,
) : RecordMemberActivityUseCase {
    override suspend fun invoke(orgId: Uuid, userId: String, at: Instant) {
        val billing = repository.get(orgId)
        val period = billingPeriod(billing, at)
        // Free organizations are counted by calendar month too: the count shows in the console and is the base of a
        // backfill if they subscribe this month.
        val first = repository.recordActivePerson(orgId, period.start, userId, at)
        if (first && billing?.hasLiveSubscription == true) queueActivity(repository, billing, period, userId, at)
    }
}

internal suspend fun queueActivity(
    repository: BillingRepository,
    billing: OrgBilling,
    period: BillingPeriod,
    userId: String,
    at: Instant,
) {
    val periodKey = period.start.epochSeconds
    // A `when` on the nullable enum would compile a default branch no value reaches.
    when {
        billing.interval == BillingInterval.MONTH -> repository.enqueue(
            OutboxTask(
                Uuid.random(),
                billing.orgId,
                OutboxKind.ACTIVE_PERSON,
                "ostrom-${billing.orgId}-$periodKey-$userId",
                at,
                0,
            ),
        )
        billing.interval == BillingInterval.YEAR &&
            repository.countActivePeople(billing.orgId, period.start) > billing.committedPeople -> {
            repository.enqueue(
                OutboxTask(
                    Uuid.random(),
                    billing.orgId,
                    OutboxKind.TRUE_UP,
                    "ostrom-${billing.orgId}-$periodKey-trueup-$userId",
                    at,
                    0,
                ),
            )
        }
    }
}

/** Adds the time of a finished (or running) cloud agent to the period and raises the 80 % and 100 % alerts once. */
interface RecordAgentUsageUseCase {
    /** @return The period's usage after the addition. */
    suspend operator fun invoke(orgId: Uuid, agentSeconds: Long, sessions: Long): Usage
}

/** [RecordAgentUsageUseCase]. */
class RecordAgentUsageUseCaseImpl(
    private val repository: BillingRepository,
    private val limits: GetPlanLimitsUseCase,
    private val notifier: BillingNotifier,
    private val clock: Clock,
) : RecordAgentUsageUseCase {
    override suspend fun invoke(orgId: Uuid, agentSeconds: Long, sessions: Long): Usage {
        require(agentSeconds >= 0 && sessions >= 0) { "Usage is never negative." }
        val now = clock.now()
        val period = billingPeriod(repository.get(orgId), now)
        val usage = repository.addUsage(orgId, period.start, agentSeconds, sessions)
        val limit = limits(orgId).agentHoursPerPeriod ?: return usage
        val percent = percentOfHours(usage.agentSeconds, limit)
        for (threshold in ALERT_THRESHOLDS.filter { it <= percent }) {
            if (repository.recordAlert(orgId, period.start, threshold, now)) {
                notifier.agentHoursReached(orgId, threshold, usage.agentHours, limit)
            }
        }
        return usage
    }
}

/** Reads the cloud agent time of an organization's current billing period, the one its hour limit applies to. */
interface GetAgentUsageUseCase {
    /** @return The period's usage so far; zero when nothing was recorded yet. */
    suspend operator fun invoke(orgId: Uuid): Usage
}

/** [GetAgentUsageUseCase]. Read only: runners call it before and while a cloud agent runs. */
class GetAgentUsageUseCaseImpl(
    private val repository: BillingRepository,
    private val clock: Clock,
) : GetAgentUsageUseCase {
    override suspend fun invoke(orgId: Uuid): Usage =
        repository.usage(orgId, billingPeriod(repository.get(orgId), clock.now()).start)
}

/** Sends the queued tasks to Stripe, with a growing delay between attempts. */
interface ProcessOutboxUseCase {
    /** @return How many tasks were sent. */
    suspend operator fun invoke(limit: Int): Int
}

/**
 * [ProcessOutboxUseCase]. Tasks are claimed for [OUTBOX_LEASE], so two replicas do not send the same task. A failure
 * is retried after 30 s, 1 min, 2 min… up to 1 h; Stripe accepts meter events up to 35 days old, and the identifier
 * makes a retry count once.
 */
class ProcessOutboxUseCaseImpl(
    private val repository: BillingRepository,
    private val stripe: StripeGateway,
    private val clock: Clock,
    private val onFailure: (OutboxTask, Exception) -> Unit,
) : ProcessOutboxUseCase {
    override suspend fun invoke(limit: Int): Int {
        var sent = 0
        val now = clock.now()
        for (task in repository.claimDueTasks(now, now + OUTBOX_LEASE, limit)) {
            try {
                send(task)
                repository.completeTask(task.id, clock.now())
                sent++
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                onFailure(task, error)
                val delay = (FIRST_RETRY * (1 shl task.attempts.coerceAtMost(MAX_DOUBLINGS))).coerceAtMost(LAST_RETRY)
                repository.failTask(task.id, clock.now() + delay)
            }
        }
        return sent
    }

    private suspend fun send(task: OutboxTask) {
        val billing = repository.get(task.orgId)
        when (task.kind) {
            // A person active before the subscription ended still belongs to its last invoice: the event is sent as
            // long as the customer exists, and Stripe bills it only inside a period.
            OutboxKind.ACTIVE_PERSON -> {
                val customerId = billing?.stripeCustomerId
                if (customerId != null) stripe.recordActivePerson(customerId, task.identifier, task.at)
            }
            OutboxKind.TRUE_UP -> billing.live()?.let { trueUp(task, it) }
        }
    }

    private suspend fun trueUp(task: OutboxTask, live: LiveSubscription) {
        val billing = live.billing
        val period = billingPeriod(billing, clock.now())
        val active = repository.countActivePeople(task.orgId, period.start)
        val itemId = billing.subscriptionItemId
        if (billing.interval == BillingInterval.YEAR && itemId != null && active > billing.committedPeople) {
            stripe.setQuantity(live.subscriptionId, itemId, active)
            repository.raiseCommittedPeople(task.orgId, active, clock.now())
        }
    }
}
