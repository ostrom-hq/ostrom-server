// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.billing.infrastructure

import dev.ostrom.server.billing.domain.BillingInterval
import dev.ostrom.server.billing.domain.BillingRepository
import dev.ostrom.server.billing.domain.OrgBilling
import dev.ostrom.server.billing.domain.OutboxKind
import dev.ostrom.server.billing.domain.OutboxTask
import dev.ostrom.server.billing.domain.PaymentIssue
import dev.ostrom.server.billing.domain.PaymentIssueKind
import dev.ostrom.server.billing.domain.Plan
import dev.ostrom.server.billing.domain.SubscriptionStatus
import dev.ostrom.server.billing.domain.Usage
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asBoolean
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstantOrNull
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import kotlin.time.Instant
import kotlin.uuid.Uuid

private const val ACCOUNT_COLUMNS = "org_id, stripe_customer_id, stripe_subscription_id, subscription_item_id, plan, " +
    "billing_interval, status, current_period_start, current_period_end, cancel_at_period_end, committed_people, " +
    "payment_issue_kind, payment_issue_url, payment_issue_at, updated_at"

/**
 * [BillingRepository] on PostgreSQL (tables of [BillingMigrations]). Every query reads by primary key or by a unique
 * or partial index, and every list is bounded.
 *
 * @param driver The database.
 */
@Suppress("TooManyFunctions") // implements BillingRepository, one function per query
class BillingRepositoryImpl(private val driver: Driver) : BillingRepository {
    override suspend fun get(orgId: Uuid): OrgBilling? = scoped(orgId) {
        fetchAll(
            Statement.create("SELECT $ACCOUNT_COLUMNS FROM billing_accounts WHERE org_id = :org_id")
                .bind("org_id", orgId),
        ).getOrThrow().firstOrNull()?.toBilling()
    }

    // The organizations of the webhook and the worker come from the cross-organization functions of
    // BillingMigrations' version 3 (S2-51): billing_customers and billing_outbox are otherwise confined.
    override suspend fun findByCustomer(customerId: String): OrgBilling? = driver.fetchAll(
        Statement.create("SELECT org_id FROM billing_org_of_customer(CAST(:customer AS TEXT))")
            .bind("customer", customerId),
    ).getOrThrow().firstOrNull()?.let { get(it.get("org_id").asUuid()) }

    override suspend fun save(billing: OrgBilling): Unit = scoped(billing.orgId) {
        billing.stripeCustomerId?.let { customerId ->
            execute(
                Statement.create(
                    "INSERT INTO billing_customers (stripe_customer_id, org_id) VALUES (:customer, :org_id) " +
                        "ON CONFLICT (stripe_customer_id) DO NOTHING",
                ).bind("customer", customerId).bind("org_id", billing.orgId),
            ).getOrThrow()
        }
        execute(
            Statement.create(
                "INSERT INTO billing_accounts ($ACCOUNT_COLUMNS) VALUES (:org_id, :customer, :subscription, :item, " +
                    ":plan, :interval, :status, :period_start, :period_end, :cancel, :committed, :issue_kind, " +
                    ":issue_url, :issue_at, :updated_at) ON CONFLICT (org_id) DO UPDATE SET " +
                    "stripe_customer_id = EXCLUDED.stripe_customer_id, " +
                    "stripe_subscription_id = EXCLUDED.stripe_subscription_id, " +
                    "subscription_item_id = EXCLUDED.subscription_item_id, plan = EXCLUDED.plan, " +
                    "billing_interval = EXCLUDED.billing_interval, status = EXCLUDED.status, " +
                    "current_period_start = EXCLUDED.current_period_start, " +
                    "current_period_end = EXCLUDED.current_period_end, " +
                    "cancel_at_period_end = EXCLUDED.cancel_at_period_end, " +
                    "committed_people = EXCLUDED.committed_people, payment_issue_kind = EXCLUDED.payment_issue_kind, " +
                    "payment_issue_url = EXCLUDED.payment_issue_url, payment_issue_at = EXCLUDED.payment_issue_at, " +
                    "updated_at = EXCLUDED.updated_at",
            )
                .bind("org_id", billing.orgId)
                .bind("customer", billing.stripeCustomerId)
                .bind("subscription", billing.stripeSubscriptionId)
                .bind("item", billing.subscriptionItemId)
                .bind("plan", billing.plan.key)
                .bind("interval", billing.interval?.key)
                .bind("status", billing.status?.stripe)
                .bind("period_start", billing.currentPeriodStart)
                .bind("period_end", billing.currentPeriodEnd)
                .bind("cancel", billing.cancelAtPeriodEnd)
                .bind("committed", billing.committedPeople)
                .bind("issue_kind", billing.paymentIssue?.let { it.kind.key })
                .bind("issue_url", billing.paymentIssue?.hostedInvoiceUrl)
                .bind("issue_at", billing.paymentIssue?.at)
                .bind("updated_at", billing.updatedAt),
        ).getOrThrow()
    }

    override suspend fun raiseCommittedPeople(orgId: Uuid, people: Int, at: Instant): Unit = scoped(orgId) {
        execute(
            Statement.create(
                "UPDATE billing_accounts SET committed_people = GREATEST(committed_people, :people), " +
                    "updated_at = :at WHERE org_id = :org_id",
            ).bind("people", people).bind("at", at).bind("org_id", orgId),
        ).getOrThrow()
    }

    override suspend fun isEventProcessed(eventId: String): Boolean = driver.fetchAll(
        Statement.create("SELECT 1 AS found FROM billing_stripe_events WHERE event_id = :id").bind("id", eventId),
    ).getOrThrow().rows.isNotEmpty()

    override suspend fun markEventProcessed(eventId: String, type: String, at: Instant) {
        driver.execute(
            Statement.create(
                "INSERT INTO billing_stripe_events (event_id, type, processed_at) VALUES (:id, :type, :at) " +
                    "ON CONFLICT (event_id) DO NOTHING",
            ).bind("id", eventId).bind("type", type).bind("at", at),
        ).getOrThrow()
    }

    override suspend fun recordActivePerson(orgId: Uuid, periodStart: Instant, userId: String, at: Instant): Boolean =
        scoped(orgId) {
            execute(
                Statement.create(
                    "INSERT INTO billing_active_people (org_id, period_start, user_id, first_active_at) " +
                        "VALUES (:org_id, :period, :user_id, :at) ON CONFLICT DO NOTHING",
                ).bind("org_id", orgId).bind("period", periodStart).bind("user_id", userId).bind("at", at),
            ).getOrThrow() > 0
        }

    override suspend fun countActivePeople(orgId: Uuid, periodStart: Instant): Int = scoped(orgId) {
        fetchAll(
            Statement.create(
                "SELECT COUNT(*) AS people FROM billing_active_people " +
                    "WHERE org_id = :org_id AND period_start = :period",
            ).bind("org_id", orgId).bind("period", periodStart),
        ).getOrThrow().first().get("people").asInt()
    }

    override suspend fun listActivePeople(orgId: Uuid, periodStart: Instant, limit: Int): List<String> = scoped(orgId) {
        fetchAll(
            Statement.create(
                "SELECT user_id FROM billing_active_people WHERE org_id = :org_id AND period_start = :period " +
                    "ORDER BY first_active_at, user_id LIMIT :limit",
            ).bind("org_id", orgId).bind("period", periodStart).bind("limit", limit),
        ).getOrThrow().map { it.get("user_id").asString() }
    }

    override suspend fun addUsage(orgId: Uuid, periodStart: Instant, agentSeconds: Long, sessions: Long): Usage =
        scoped(orgId) {
            fetchAll(
                Statement.create(
                    "INSERT INTO billing_usage (org_id, period_start, agent_seconds, sessions) " +
                        "VALUES (:org_id, :period, :seconds, :sessions) " +
                        "ON CONFLICT (org_id, period_start) DO UPDATE SET " +
                        "agent_seconds = billing_usage.agent_seconds + EXCLUDED.agent_seconds, " +
                        "sessions = billing_usage.sessions + EXCLUDED.sessions RETURNING agent_seconds, sessions",
                ).bind("org_id", orgId).bind("period", periodStart)
                    .bind("seconds", agentSeconds).bind("sessions", sessions),
            ).getOrThrow().first().toUsage(periodStart)
        }

    override suspend fun usage(orgId: Uuid, periodStart: Instant): Usage = scoped(orgId) {
        fetchAll(
            Statement.create(
                "SELECT agent_seconds, sessions FROM billing_usage WHERE org_id = :org_id AND period_start = :period",
            ).bind("org_id", orgId).bind("period", periodStart),
        ).getOrThrow().firstOrNull().let { row ->
            if (row == null) Usage(periodStart, 0, 0) else row.toUsage(periodStart)
        }
    }

    override suspend fun recordAlert(orgId: Uuid, periodStart: Instant, percent: Int, at: Instant): Boolean =
        scoped(orgId) {
            execute(
                Statement.create(
                    "INSERT INTO billing_alerts (org_id, period_start, percent, raised_at) " +
                        "VALUES (:org_id, :period, :percent, :at) ON CONFLICT DO NOTHING",
                ).bind("org_id", orgId).bind("period", periodStart).bind("percent", percent).bind("at", at),
            ).getOrThrow() > 0
        }

    override suspend fun alerts(orgId: Uuid, periodStart: Instant): List<Int> = scoped(orgId) {
        fetchAll(
            Statement.create(
                "SELECT percent FROM billing_alerts WHERE org_id = :org_id AND period_start = :period " +
                    "ORDER BY percent",
            ).bind("org_id", orgId).bind("period", periodStart),
        ).getOrThrow().map { it.get("percent").asInt() }
    }

    override suspend fun enqueue(task: OutboxTask): Unit = scoped(task.orgId) {
        execute(
            Statement.create(
                "INSERT INTO billing_outbox (id, org_id, kind, identifier, happened_at, attempts, next_attempt_at) " +
                    "VALUES (:id, :org_id, :kind, :identifier, :at, :attempts, :at) " +
                    "ON CONFLICT (identifier) DO NOTHING",
            )
                .bind("id", task.id)
                .bind("org_id", task.orgId)
                .bind("kind", task.kind.key)
                .bind("identifier", task.identifier)
                .bind("at", task.at)
                .bind("attempts", task.attempts),
        ).getOrThrow()
    }

    // SKIP LOCKED lets two replicas claim at the same time without waiting for, or taking, each other's rows.
    override suspend fun claimDueTasks(
        now: Instant,
        leaseUntil: Instant,
        limit: Int,
    ): List<OutboxTask> = driver.fetchAll(
        Statement.create(
            "SELECT id, org_id, kind, identifier, happened_at, attempts FROM billing_claim_outbox(" +
                "CAST(:now AS TIMESTAMPTZ), CAST(:lease AS TIMESTAMPTZ), CAST(:limit AS INTEGER))",
        ).bind("now", now).bind("lease", leaseUntil).bind("limit", limit),
    ).getOrThrow().sortedBy { it.get("happened_at").asInstant() }.map {
        OutboxTask(
            id = it.get("id").asUuid(),
            orgId = it.get("org_id").asUuid(),
            kind = OutboxKind.fromKey(it.get("kind").asString()),
            identifier = it.get("identifier").asString(),
            at = it.get("happened_at").asInstant(),
            attempts = it.get("attempts").asInt(),
        )
    }

    override suspend fun completeTask(id: Uuid, at: Instant) {
        driver.fetchAll(
            Statement.create("SELECT billing_complete_outbox(CAST(:id AS UUID), CAST(:at AS TIMESTAMPTZ))")
                .bind("id", id).bind("at", at),
        ).getOrThrow()
    }

    override suspend fun failTask(id: Uuid, retryAt: Instant) {
        driver.fetchAll(
            Statement.create("SELECT billing_fail_outbox(CAST(:id AS UUID), CAST(:retry AS TIMESTAMPTZ))")
                .bind("id", id).bind("retry", retryAt),
        ).getOrThrow()
    }

    /**
     * Runs [block] in a transaction whose `ostrom.org_id` is [orgId]: the row-level policies of [BillingMigrations]
     * show and accept only that organization's rows (S2-01).
     */
    private suspend fun <T> scoped(orgId: Uuid, block: suspend QueryExecutor.() -> T): T = driver.transaction {
        fetchAll(Statement.create("SELECT set_config('ostrom.org_id', :org, TRUE)").bind("org", orgId.toString()))
            .getOrThrow()
        block()
    }

    private fun ResultSet.Row.toBilling(): OrgBilling {
        val issueKind = get("payment_issue_kind").asStringOrNull()
            ?.let { key -> PaymentIssueKind.entries.first { it.key == key } }
        return OrgBilling(
            orgId = get("org_id").asUuid(),
            stripeCustomerId = get("stripe_customer_id").asStringOrNull(),
            stripeSubscriptionId = get("stripe_subscription_id").asStringOrNull(),
            subscriptionItemId = get("subscription_item_id").asStringOrNull(),
            plan = Plan.entries.first { it.key == get("plan").asString() },
            interval = get("billing_interval").asStringOrNull()?.let(BillingInterval::fromKey),
            status = get("status").asStringOrNull()?.let(SubscriptionStatus::fromStripe),
            currentPeriodStart = get("current_period_start").asInstantOrNull(),
            currentPeriodEnd = get("current_period_end").asInstantOrNull(),
            cancelAtPeriodEnd = get("cancel_at_period_end").asBoolean(),
            committedPeople = get("committed_people").asInt(),
            paymentIssue = issueKind?.let {
                PaymentIssue(it, get("payment_issue_url").asStringOrNull(), get("payment_issue_at").asInstant())
            },
            updatedAt = get("updated_at").asInstant(),
        )
    }

    private fun ResultSet.Row.toUsage(periodStart: Instant) =
        Usage(periodStart, get("agent_seconds").asLong(), get("sessions").asLong())
}
