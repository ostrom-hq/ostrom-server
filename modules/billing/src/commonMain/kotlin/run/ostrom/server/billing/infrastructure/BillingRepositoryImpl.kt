// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.billing.infrastructure

import run.ostrom.server.billing.domain.BillingInterval
import run.ostrom.server.billing.domain.BillingRepository
import run.ostrom.server.billing.domain.OrgBilling
import run.ostrom.server.billing.domain.OutboxKind
import run.ostrom.server.billing.domain.OutboxTask
import run.ostrom.server.billing.domain.PaymentIssue
import run.ostrom.server.billing.domain.PaymentIssueKind
import run.ostrom.server.billing.domain.Plan
import run.ostrom.server.billing.domain.SubscriptionStatus
import run.ostrom.server.billing.domain.Usage
import run.ostrom.server.kernel.database.InstantColumnType
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.present
import run.ostrom.server.kernel.database.withOrganization
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.QueryParameter
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.UuidColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import org.jetbrains.exposed.v1.jdbc.upsertReturning
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [BillingRepository] on PostgreSQL (tables of [BillingMigrations]). Every query reads by primary
 * key or by a unique or partial index, and every list is bounded. An organization's rows are read and written by the
 * serving role with the organization set (`withOrganization`, the forced row-level policies of [BillingMigrations]
 * showing and accepting only its rows, S2-01), each query filtered on `org_id` too; what crosses organizations goes
 * through the functions of `ostrom_router`.
 *
 * @param database The serving role's pool.
 */
@Suppress("TooManyFunctions") // implements BillingRepository, one function per query
class BillingRepositoryImpl(private val database: JdbcDatabase) : BillingRepository {
    override suspend fun get(orgId: Uuid): OrgBilling? = database.withOrganization(orgId) {
        val a = BillingAccounts
        a.select(a.columns).where { a.orgId eq orgId }.singleOrNull()?.toBilling()
    }

    override suspend fun findByCustomer(customerId: String): OrgBilling? = database.query {
        val org = CustomFunction(
            "billing_customer_org",
            UuidColumnType().apply { nullable = true },
            QueryParameter(customerId, TextColumnType()),
        )
        Table.Dual.select(org).single()[org]
    }?.let { get(it) }

    override suspend fun save(billing: OrgBilling): Unit = database.withOrganization(billing.orgId) {
        billing.stripeCustomerId?.let { customerId ->
            // ON CONFLICT DO NOTHING: the customer's one key is its primary key.
            BillingCustomers.insertIgnore {
                it[stripeCustomerId] = customerId
                it[orgId] = billing.orgId
            }
        }
        val a = BillingAccounts
        a.upsert(a.orgId) {
            it[orgId] = billing.orgId
            it[stripeCustomerId] = billing.stripeCustomerId
            it[stripeSubscriptionId] = billing.stripeSubscriptionId
            it[subscriptionItemId] = billing.subscriptionItemId
            it[plan] = billing.plan.key
            it[billingInterval] = billing.interval?.key
            it[status] = billing.status?.stripe
            it[currentPeriodStart] = billing.currentPeriodStart
            it[currentPeriodEnd] = billing.currentPeriodEnd
            it[cancelAtPeriodEnd] = billing.cancelAtPeriodEnd
            it[committedPeople] = billing.committedPeople
            it[paymentIssueKind] = billing.paymentIssue?.let { issue -> issue.kind.key }
            it[paymentIssueUrl] = billing.paymentIssue?.hostedInvoiceUrl
            it[paymentIssueAt] = billing.paymentIssue?.at
            it[updatedAt] = billing.updatedAt
        }
    }

    override suspend fun raiseCommittedPeople(orgId: Uuid, people: Int, at: Instant): Unit =
        database.withOrganization(orgId) {
            val a = BillingAccounts
            a.update({ a.orgId eq orgId }) {
                it[committedPeople] =
                    CustomFunction("GREATEST", IntegerColumnType(), a.committedPeople, intParam(people))
                it[updatedAt] = at
            }
        }

    override suspend fun isEventProcessed(eventId: String): Boolean = database.query {
        val e = BillingStripeEvents
        e.select(e.eventId).where { e.eventId eq eventId }.limit(1).any()
    }

    override suspend fun markEventProcessed(eventId: String, type: String, at: Instant) {
        database.query {
            BillingStripeEvents.insertIgnore {
                it[this.eventId] = eventId
                it[this.type] = type
                it[processedAt] = at
            }
        }
    }

    override suspend fun recordActivePerson(orgId: Uuid, periodStart: Instant, userId: String, at: Instant): Boolean =
        database.withOrganization(orgId) {
            BillingActivePeople.insertIgnore {
                it[this.orgId] = orgId
                it[this.periodStart] = periodStart
                it[this.userId] = userId
                it[firstActiveAt] = at
            }.insertedCount > 0
        }

    override suspend fun countActivePeople(orgId: Uuid, periodStart: Instant): Int = database.withOrganization(orgId) {
        val p = BillingActivePeople
        val people = p.userId.count()
        p.select(people).where { (p.orgId eq orgId) and (p.periodStart eq periodStart) }.single()[people].toInt()
    }

    override suspend fun listActivePeople(orgId: Uuid, periodStart: Instant, limit: Int): List<String> =
        database.withOrganization(orgId) {
            val p = BillingActivePeople
            p.select(p.userId)
                .where { (p.orgId eq orgId) and (p.periodStart eq periodStart) }
                .orderBy(p.firstActiveAt to SortOrder.ASC, p.userId to SortOrder.ASC)
                .limit(limit)
                .map { it[p.userId] }
        }

    override suspend fun addUsage(orgId: Uuid, periodStart: Instant, agentSeconds: Long, sessions: Long): Usage =
        database.withOrganization(orgId) {
            val u = BillingUsage
            u.upsertReturning(
                u.orgId, u.periodStart,
                returning = listOf(u.agentSeconds, u.sessions),
                onUpdate = {
                    it[u.agentSeconds] = u.agentSeconds + insertValue(u.agentSeconds)
                    it[u.sessions] = u.sessions + insertValue(u.sessions)
                },
            ) {
                it[this.orgId] = orgId
                it[this.periodStart] = periodStart
                it[this.agentSeconds] = agentSeconds
                it[this.sessions] = sessions
            }.single().toUsage(periodStart)
        }

    override suspend fun usage(orgId: Uuid, periodStart: Instant): Usage = database.withOrganization(orgId) {
        val u = BillingUsage
        u.select(u.agentSeconds, u.sessions)
            .where { (u.orgId eq orgId) and (u.periodStart eq periodStart) }
            .singleOrNull()
            .let { row -> if (row == null) Usage(periodStart, 0, 0) else row.toUsage(periodStart) }
    }

    override suspend fun recordAlert(orgId: Uuid, periodStart: Instant, percent: Int, at: Instant): Boolean =
        database.withOrganization(orgId) {
            BillingAlerts.insertIgnore {
                it[this.orgId] = orgId
                it[this.periodStart] = periodStart
                it[this.percent] = percent
                it[raisedAt] = at
            }.insertedCount > 0
        }

    override suspend fun alerts(orgId: Uuid, periodStart: Instant): List<Int> = database.withOrganization(orgId) {
        val a = BillingAlerts
        a.select(a.percent)
            .where { (a.orgId eq orgId) and (a.periodStart eq periodStart) }
            .orderBy(a.percent to SortOrder.ASC)
            .map { it[a.percent] }
    }

    override suspend fun enqueue(task: OutboxTask): Unit = database.withOrganization(task.orgId) {
        // ON CONFLICT DO NOTHING: a task's identifier is enqueued once (its id is a new random one).
        BillingOutbox.insertIgnore {
            it[id] = task.id
            it[orgId] = task.orgId
            it[kind] = task.kind.key
            it[identifier] = task.identifier
            it[happenedAt] = task.at
            it[attempts] = task.attempts
            it[nextAttemptAt] = task.at
        }
    }

    // SKIP LOCKED lets two replicas claim at the same time without waiting for, or taking, each other's rows. The
    // queue spans organizations: it is claimed, completed and retried only through the functions of version 3.
    override suspend fun claimDueTasks(
        now: Instant,
        leaseUntil: Instant,
        limit: Int,
    ): List<OutboxTask> = database.query {
        val d = BillingDueTasks
        d.of(now, leaseUntil, limit)
            .select(d.id, d.orgId, d.kind, d.identifier, d.happenedAt, d.attempts)
            .map {
                OutboxTask(
                    id = it[d.id],
                    orgId = it[d.orgId],
                    kind = OutboxKind.fromKey(it[d.kind]),
                    identifier = it[d.identifier],
                    at = it[d.happenedAt],
                    attempts = it[d.attempts],
                )
            }
    }.sortedBy { it.at }

    override suspend fun completeTask(id: Uuid, at: Instant) {
        database.query { call("billing_complete_task", uuidParam(id), instantParam(at)) }
    }

    override suspend fun failTask(id: Uuid, retryAt: Instant) {
        database.query { call("billing_fail_task", uuidParam(id), instantParam(retryAt)) }
    }

    private fun ResultRow.toBilling(): OrgBilling {
        val a = BillingAccounts
        val issueKind = this[a.paymentIssueKind]?.let { key -> PaymentIssueKind.entries.first { it.key == key } }
        return OrgBilling(
            orgId = this[a.orgId],
            stripeCustomerId = this[a.stripeCustomerId],
            stripeSubscriptionId = this[a.stripeSubscriptionId],
            subscriptionItemId = this[a.subscriptionItemId],
            plan = Plan.entries.first { it.key == this[a.plan] },
            interval = this[a.billingInterval]?.let(BillingInterval::fromKey),
            status = this[a.status]?.let(SubscriptionStatus::fromStripe),
            currentPeriodStart = this[a.currentPeriodStart],
            currentPeriodEnd = this[a.currentPeriodEnd],
            cancelAtPeriodEnd = this[a.cancelAtPeriodEnd],
            committedPeople = this[a.committedPeople],
            paymentIssue = issueKind?.let {
                PaymentIssue(it, this[a.paymentIssueUrl], present(a.paymentIssueAt, a.paymentIssueKind))
            },
            updatedAt = this[a.updatedAt],
        )
    }

    private fun ResultRow.toUsage(periodStart: Instant) =
        Usage(periodStart, this[BillingUsage.agentSeconds], this[BillingUsage.sessions])
}

private fun intParam(value: Int): Expression<Int> = QueryParameter(value, IntegerColumnType())

private fun uuidParam(value: Uuid): Expression<Uuid> = QueryParameter(value, UuidColumnType())

private fun instantParam(value: Instant): Expression<Instant> = QueryParameter(value, InstantColumnType())

/** Calls a function of `ostrom_router` that returns nothing (`SELECT name(args…)`). */
private fun JdbcTransaction.call(name: String, vararg arguments: Expression<*>) {
    Table.Dual.select(CustomFunction(name, TextColumnType(), *arguments)).toList()
}
