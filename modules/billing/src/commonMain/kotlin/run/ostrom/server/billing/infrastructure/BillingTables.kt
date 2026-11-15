// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.billing.infrastructure

import run.ostrom.server.kernel.database.InstantColumnType
import run.ostrom.server.kernel.database.instant
import org.jetbrains.exposed.v1.core.ColumnSet
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.Transaction
import kotlin.time.Instant

/*
 * The tables of [BillingMigrations], as Exposed reads and writes them. The migrations create the schema; these objects
 * only name its columns and their types. Every table that carries an organization is under forced row-level security,
 * read and written by the serving role with the organization set (`withOrganization`), each query filtered on
 * `org_id` too; `billing_customers` and `billing_outbox` are also used across organizations, only through the
 * functions of `ostrom_router` (version 3).
 */

/** An organization's subscription. */
internal object BillingAccounts : Table("billing_accounts") {
    val orgId = uuid("org_id")
    val stripeCustomerId = text("stripe_customer_id").nullable()
    val stripeSubscriptionId = text("stripe_subscription_id").nullable()
    val subscriptionItemId = text("subscription_item_id").nullable()
    val plan = text("plan")
    val billingInterval = text("billing_interval").nullable()
    val status = text("status").nullable()
    val currentPeriodStart = instant("current_period_start").nullable()
    val currentPeriodEnd = instant("current_period_end").nullable()
    val cancelAtPeriodEnd = bool("cancel_at_period_end")
    val committedPeople = integer("committed_people")
    val paymentIssueKind = text("payment_issue_kind").nullable()
    val paymentIssueUrl = text("payment_issue_url").nullable()
    val paymentIssueAt = instant("payment_issue_at").nullable()
    val updatedAt = instant("updated_at")

    override val primaryKey = PrimaryKey(orgId)
}

/** The organization of each Stripe customer, written with its account. */
internal object BillingCustomers : Table("billing_customers") {
    val stripeCustomerId = text("stripe_customer_id")
    val orgId = uuid("org_id")

    override val primaryKey = PrimaryKey(stripeCustomerId)
}

/** The Stripe events already processed: no organization's. */
internal object BillingStripeEvents : Table("billing_stripe_events") {
    val eventId = text("event_id")
    val type = text("type")
    val processedAt = instant("processed_at")

    override val primaryKey = PrimaryKey(eventId)
}

/** The people active in a billing period. */
internal object BillingActivePeople : Table("billing_active_people") {
    val orgId = uuid("org_id")
    val periodStart = instant("period_start")
    val userId = text("user_id")
    val firstActiveAt = instant("first_active_at")

    override val primaryKey = PrimaryKey(orgId, periodStart, userId)
}

/** The cloud agent usage of a billing period. */
internal object BillingUsage : Table("billing_usage") {
    val orgId = uuid("org_id")
    val periodStart = instant("period_start")
    val agentSeconds = long("agent_seconds")
    val sessions = long("sessions")

    override val primaryKey = PrimaryKey(orgId, periodStart)
}

/** The usage alerts raised in a billing period, one per percent. */
internal object BillingAlerts : Table("billing_alerts") {
    val orgId = uuid("org_id")
    val periodStart = instant("period_start")
    val percent = integer("percent")
    val raisedAt = instant("raised_at")

    override val primaryKey = PrimaryKey(orgId, periodStart, percent)
}

/** The worker's queue of what to report to Stripe, one task per identifier. */
internal object BillingOutbox : Table("billing_outbox") {
    val id = uuid("id")
    val orgId = uuid("org_id")
    val kind = text("kind")
    val identifier = text("identifier")
    val happenedAt = instant("happened_at")
    val attempts = integer("attempts")
    val nextAttemptAt = instant("next_attempt_at")
    val doneAt = instant("done_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

/**
 * What `billing_claim_due_tasks(now, lease, limit)` returns (version 3), read as a table: the tasks it claimed, across
 * organizations.
 */
internal object BillingDueTasks : Table("billing_claim_due_tasks") {
    val id = uuid("id")
    val orgId = uuid("org_id")
    val kind = text("kind")
    val identifier = text("identifier")
    val happenedAt = instant("happened_at")
    val attempts = integer("attempts")

    /**
     * The function called with its arguments, to select the columns from.
     *
     * @param now The time a task is due by.
     * @param lease Until when the claimed tasks are leased.
     * @param limit The most tasks claimed.
     */
    fun of(now: Instant, lease: Instant, limit: Int): ColumnSet = Call(now, lease, limit)

    private class Call(private val now: Instant, private val lease: Instant, private val limit: Int) :
        Table(BillingDueTasks.tableName) {
        override fun describe(s: Transaction, queryBuilder: QueryBuilder): Unit = queryBuilder {
            append(tableName)
            append('(')
            registerArgument(InstantColumnType(), now)
            append(", ")
            registerArgument(InstantColumnType(), lease)
            append(", ")
            registerArgument(IntegerColumnType(), limit)
            append(')')
        }
    }
}
