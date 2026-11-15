// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.events.infrastructure

import run.ostrom.server.events.domain.DeliveryOutcome
import run.ostrom.server.events.domain.DeliveryState
import run.ostrom.server.events.domain.DueDelivery
import run.ostrom.server.events.domain.JournalEvent
import run.ostrom.server.events.domain.StaleWebhookVersionException
import run.ostrom.server.events.domain.WebhookChange
import run.ostrom.server.events.domain.WebhookDeliveryRecord
import run.ostrom.server.events.domain.WebhookLimitException
import run.ostrom.server.events.domain.WebhookNotFoundException
import run.ostrom.server.events.domain.WebhookRules
import run.ostrom.server.events.domain.WebhookSubscription
import run.ostrom.server.events.domain.WebhookTestRateException
import run.ostrom.server.events.domain.WebhooksRepository
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.withOrganization
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.updateReturning
import org.jetbrains.exposed.v1.jdbc.upsertReturning
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [WebhooksRepository] on PostgreSQL (version 2 of [EventsMigrations]). Every call runs in a transaction confined to
 * its organization (the serving role with the organization set, `withOrganization`), each query filtered on `org_id`
 * too, except the two router functions. A webhook's row is locked before its deliveries' everywhere (as a delete,
 * which cascades to them, does), so two replicas never deadlock.
 *
 * @param database The serving role's pool.
 */
@Suppress("TooManyFunctions") // One per step of the management and of the worker.
class WebhooksRepositoryImpl(private val database: JdbcDatabase) : WebhooksRepository {
    override suspend fun list(orgId: Uuid): List<WebhookSubscription> = database.withOrganization(orgId) {
        val w = EventsWebhooks
        w.select(WEBHOOK_COLUMNS)
            .where { w.orgId eq orgId }
            .orderBy(w.createdAt to SortOrder.ASC, w.id to SortOrder.ASC)
            .limit(WebhookRules.MAX_PER_ORG)
            .map { it.toWebhook() }
    }

    override suspend fun find(orgId: Uuid, id: Uuid): WebhookSubscription? = database.withOrganization(orgId) {
        webhook(orgId, id, lock = false)
    }

    override suspend fun create(subscription: WebhookSubscription, sealedSecret: String): WebhookSubscription {
        val orgId = subscription.orgId
        return database.withOrganization(orgId) {
            // The cursor row, locked: creations of the organization take turns (the limit holds), and the webhook
            // starts at the head its publishers cannot move meanwhile.
            val c = EventsCursors
            val head = c.upsertReturning(
                c.orgId,
                returning = listOf(c.lastSeq),
                onUpdate = { it[c.lastSeq] = c.lastSeq },
            ) {
                it[c.orgId] = orgId
                it[c.lastSeq] = 0
            }.single()[c.lastSeq]
            val w = EventsWebhooks
            val count = w.select(w.id).where { w.orgId eq orgId }.count()
            if (count >= WebhookRules.MAX_PER_ORG) throw WebhookLimitException()
            w.insertReturning(WEBHOOK_COLUMNS) {
                it[id] = subscription.id
                it[this.orgId] = orgId
                it[url] = subscription.url
                it[types] = encode(subscription.types)
                it[description] = subscription.description
                it[enabled] = true
                it[disabledReason] = null
                it[secret] = sealedSecret
                it[createdBy] = subscription.createdBy
                it[createdAt] = subscription.createdAt
                it[updatedAt] = subscription.createdAt
                it[version] = 1
                it[fanoutSeq] = head
                it[failures] = 0
            }.single().toWebhook()
        }
    }

    override suspend fun update(orgId: Uuid, id: Uuid, change: WebhookChange): WebhookSubscription =
        database.withOrganization(orgId) {
            val current = webhook(orgId, id, lock = true) ?: throw WebhookNotFoundException()
            if (current.version != change.version) throw StaleWebhookVersionException()
            val enable = change.enabled ?: current.enabled
            // Enabled again: the events of the time it was off are not replayed, and its failures start over.
            val again = enable && !current.enabled
            val fanout = if (again) headOf(orgId) else current.fanoutSeq
            val w = EventsWebhooks
            w.updateReturning(WEBHOOK_COLUMNS, where = { (w.orgId eq orgId) and (w.id eq id) }) {
                it[url] = change.url
                it[types] = encode(change.types)
                it[description] = change.description
                it[enabled] = enable
                it[disabledReason] = if (enable) null else current.disabledReason ?: PAUSED
                it[updatedAt] = change.at
                it[version] = version + 1
                it[fanoutSeq] = fanout
                it[failures] = if (again) 0 else current.failures
            }.single().toWebhook()
        }

    override suspend fun rotateSecret(orgId: Uuid, id: Uuid, sealedSecret: String, at: Instant): WebhookSubscription =
        database.withOrganization(orgId) {
            val w = EventsWebhooks
            val row = w.updateReturning(WEBHOOK_COLUMNS, where = { (w.orgId eq orgId) and (w.id eq id) }) {
                it[secret] = sealedSecret
                it[updatedAt] = at
                it[version] = version + 1
            }.singleOrNull() ?: throw WebhookNotFoundException()
            row.toWebhook()
        }

    override suspend fun delete(orgId: Uuid, id: Uuid) {
        database.withOrganization(orgId) {
            val gone = EventsWebhooks.deleteWhere { (EventsWebhooks.orgId eq orgId) and (EventsWebhooks.id eq id) }
            if (gone == 0) throw WebhookNotFoundException()
        }
    }

    override suspend fun deliveries(orgId: Uuid, id: Uuid, limit: Int): List<WebhookDeliveryRecord> =
        database.withOrganization(orgId) {
            val d = EventsWebhookDeliveries
            d.select(DELIVERY_COLUMNS)
                .where { (d.orgId eq orgId) and (d.webhookId eq id) }
                .orderBy(d.createdAt to SortOrder.DESC, d.id to SortOrder.DESC)
                .limit(limit)
                .map { it.toDelivery() }
        }

    override suspend fun enqueueTest(orgId: Uuid, delivery: WebhookDeliveryRecord): WebhookDeliveryRecord =
        database.withOrganization(orgId) {
            webhook(orgId, delivery.subscriptionId, lock = true) ?: throw WebhookNotFoundException()
            // A test is not a pending event: a flood of them is bounded by the rate, under the webhook's lock.
            val d = EventsWebhookDeliveries
            val since = delivery.createdAt - WebhookRules.TEST_WINDOW
            val recent = d.select(d.id)
                .where {
                    (d.orgId eq orgId) and (d.webhookId eq delivery.subscriptionId) and (d.test eq true) and
                        (d.createdAt greater since)
                }
                .count()
            if (recent >= WebhookRules.MAX_TESTS) throw WebhookTestRateException()
            insertDelivery(orgId, delivery).single()
        }

    override suspend fun workingOrgs(now: Instant, limit: Int): List<Uuid> = database.query {
        val o = EventsWebhookOrgs
        o.of(now, limit).select(o.orgId).map { it[o.orgId] }
    }

    override suspend fun fanOut(
        orgId: Uuid,
        perWebhook: Int,
        now: Instant,
        payload: (Uuid, JournalEvent) -> String,
    ): Int = database.withOrganization(orgId) {
        val head = headOf(orgId)
        val w = EventsWebhooks
        // Locked, skipping one another replica is turning into deliveries right now.
        val behind = w.select(w.id, w.types, w.fanoutSeq)
            .where { (w.orgId eq orgId) and (w.enabled eq true) and (w.fanoutSeq less head) }
            .orderBy(w.id to SortOrder.ASC)
            .forUpdate(ForUpdateOption.PostgreSQL.ForUpdate(ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED))
            .map { Triple(it[w.id], it[w.types], it[w.fanoutSeq]) }
        var queued = 0
        for ((id, types, from) in behind) {
            val j = EventsJournal
            val rows = j.select(j.seq, j.type, j.subject, j.subjectId, j.memberId, j.at, j.data)
                .where {
                    (j.orgId eq orgId) and (j.seq greater from) and (j.seq lessEq head) and
                        (j.type inList types.split(','))
                }
                .orderBy(j.seq to SortOrder.ASC)
                .limit(perWebhook + 1)
                .map { it.toJournalEvent() }
            val page = rows.take(perWebhook)
            for (event in page) {
                val deliveryId = Uuid.random()
                val delivery = eventDelivery(deliveryId, id, event, payload(deliveryId, event), now)
                queued += insertDelivery(orgId, delivery).size
            }
            // A full page resumes after its last event; otherwise everything up to the head was looked at.
            val through = if (rows.size > perWebhook) page.last().seq else head
            w.update({ (w.orgId eq orgId) and (w.id eq id) }) { it[fanoutSeq] = through }
        }
        queued
    }

    override suspend fun claimDue(orgId: Uuid, limit: Int, now: Instant, lease: Duration): List<DueDelivery> =
        database.withOrganization(orgId) {
            val d = EventsWebhookDeliveries
            val w = EventsWebhooks
            // The due deliveries of a webhook still on (or tests), locked, skipping those another replica claims, and
            // where each goes; then their lease moves, in the same transaction.
            val targets = d.join(w, JoinType.INNER, d.webhookId, w.id)
                .select(d.id, w.url, w.secret)
                .where {
                    (d.orgId eq orgId) and (d.state eq DeliveryState.PENDING.wire) and (d.nextAttemptAt lessEq now) and
                        (d.leaseUntil lessEq now) and ((w.enabled eq true) or (d.test eq true))
                }
                .orderBy(d.nextAttemptAt to SortOrder.ASC)
                .limit(limit)
                .forUpdate(ForUpdateOption.PostgreSQL.ForUpdate(ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED, d))
                .associate { it[d.id] to (it[w.url] to it[w.secret]) }
            if (targets.isEmpty()) return@withOrganization emptyList()
            d.updateReturning(DELIVERY_COLUMNS, where = { (d.orgId eq orgId) and (d.id inList targets.keys) }) {
                it[leaseUntil] = now + lease
            }.map { row ->
                val (url, secret) = targets.getValue(row[d.id])
                DueDelivery(orgId, row.toDelivery(), url, secret)
            }
        }

    override suspend fun record(orgId: Uuid, id: Uuid, outcome: DeliveryOutcome) {
        database.withOrganization(orgId) {
            val d = EventsWebhookDeliveries
            val w = EventsWebhooks
            // The webhook's row first, as everywhere; a delivery deleted meanwhile (with its webhook) is gone.
            val owner = d.join(w, JoinType.INNER, d.webhookId, w.id)
                .select(d.webhookId, d.test, w.failures, w.enabled)
                .where { (d.orgId eq orgId) and (d.id eq id) }
                .forUpdate(ForUpdateOption.PostgreSQL.ForUpdate(null, w))
                .singleOrNull() ?: return@withOrganization
            val webhookId = owner[d.webhookId]
            val result = outcome.result
            d.update({ (d.orgId eq orgId) and (d.id eq id) }) {
                it[state] = outcome.state.wire
                it[attempts] = outcome.attempts
                it[nextAttemptAt] = outcome.nextAttemptAt
                it[leaseUntil] = outcome.at
                it[lastStatus] = result.status
                it[lastError] = result.error?.take(MAX_ERROR)
                it[deliveredAt] = outcome.at.takeIf { outcome.state == DeliveryState.DELIVERED }
            }
            // Tests say nothing of the health of the webhook's real traffic.
            if (owner[d.test] || outcome.state == DeliveryState.PENDING) return@withOrganization
            val failures = if (outcome.state == DeliveryState.DELIVERED) 0 else owner[w.failures] + 1
            val disable = owner[w.enabled] && failures >= WebhookRules.DISABLE_AFTER
            w.update({ (w.orgId eq orgId) and (w.id eq webhookId) }) {
                it[this.failures] = failures
                if (disable) {
                    it[enabled] = false
                    it[disabledReason] = TOO_MANY_FAILURES
                    it[updatedAt] = outcome.at
                    it[version] = version + 1
                }
            }
        }
    }

    override suspend fun maintain(now: Instant, giveUpBefore: Instant, deleteBefore: Instant, limit: Int): Int =
        database.query {
            countOf(
                "events_webhook_maintain",
                instantParam(now),
                instantParam(giveUpBefore),
                instantParam(deleteBefore),
                intParam(limit),
            ).toInt()
        }

    override suspend fun erase(orgId: Uuid) {
        database.withOrganization(orgId) {
            EventsWebhookDeliveries.deleteWhere { EventsWebhookDeliveries.orgId eq orgId }
            EventsWebhooks.deleteWhere { EventsWebhooks.orgId eq orgId }
        }
    }

    private companion object {
        /** A paused webhook's reason. */
        const val PAUSED = "disabled"

        /** An automatically disabled webhook's reason. */
        const val TOO_MANY_FAILURES = "too_many_failures"

        /** The longest error code kept. */
        const val MAX_ERROR = 40

        /** What a webhook's reads return: every column but its sealed secret. */
        val WEBHOOK_COLUMNS = EventsWebhooks.let { w ->
            listOf(
                w.id, w.orgId, w.url, w.types, w.description, w.enabled, w.disabledReason, w.createdBy, w.createdAt,
                w.updatedAt, w.version, w.fanoutSeq, w.failures,
            )
        }

        /** What a delivery's reads return: every column but its lease and its organization. */
        val DELIVERY_COLUMNS = EventsWebhookDeliveries.let { d ->
            listOf(
                d.id, d.webhookId, d.eventSeq, d.eventType, d.payload, d.state, d.attempts, d.nextAttemptAt,
                d.lastStatus, d.lastError, d.createdAt, d.deliveredAt, d.test,
            )
        }

        fun eventDelivery(id: Uuid, webhookId: Uuid, event: JournalEvent, payload: String, now: Instant) =
            WebhookDeliveryRecord(
                id = id,
                subscriptionId = webhookId,
                eventSeq = event.seq,
                eventType = event.type,
                payload = payload,
                state = DeliveryState.PENDING,
                attempts = 0,
                nextAttemptAt = now,
                lastStatus = null,
                lastError = null,
                createdAt = now,
                deliveredAt = null,
                test = false,
            )

        fun encode(types: Set<String>): String = types.sorted().joinToString(",")

        fun JdbcTransaction.webhook(orgId: Uuid, id: Uuid, lock: Boolean): WebhookSubscription? {
            val w = EventsWebhooks
            val query = w.select(WEBHOOK_COLUMNS).where { (w.orgId eq orgId) and (w.id eq id) }
            return (if (lock) query.forUpdate() else query).singleOrNull()?.toWebhook()
        }

        /** Queues [delivery] once per event (`ON CONFLICT DO NOTHING`): the delivery, or none when it was queued. */
        fun JdbcTransaction.insertDelivery(orgId: Uuid, delivery: WebhookDeliveryRecord): List<WebhookDeliveryRecord> =
            EventsWebhookDeliveries.insertReturning(DELIVERY_COLUMNS, ignoreErrors = true) {
                it.delivery(orgId, delivery)
            }.map { it.toDelivery() }

        fun UpdateBuilder<*>.delivery(orgId: Uuid, delivery: WebhookDeliveryRecord) {
            val d = EventsWebhookDeliveries
            this[d.id] = delivery.id
            this[d.orgId] = orgId
            this[d.webhookId] = delivery.subscriptionId
            this[d.eventSeq] = delivery.eventSeq
            this[d.eventType] = delivery.eventType
            this[d.payload] = delivery.payload
            this[d.state] = delivery.state.wire
            this[d.attempts] = 0
            this[d.nextAttemptAt] = delivery.createdAt
            this[d.leaseUntil] = delivery.createdAt
            this[d.lastStatus] = null
            this[d.lastError] = null
            this[d.createdAt] = delivery.createdAt
            this[d.deliveredAt] = null
            this[d.test] = delivery.test
        }

        fun ResultRow.toWebhook(): WebhookSubscription {
            val w = EventsWebhooks
            return WebhookSubscription(
                id = this[w.id],
                orgId = this[w.orgId],
                url = this[w.url],
                types = this[w.types].split(',').toSet(),
                description = this[w.description],
                enabled = this[w.enabled],
                disabledReason = this[w.disabledReason],
                createdBy = this[w.createdBy],
                createdAt = this[w.createdAt],
                updatedAt = this[w.updatedAt],
                version = this[w.version],
                fanoutSeq = this[w.fanoutSeq],
                failures = this[w.failures],
            )
        }

        fun ResultRow.toDelivery(): WebhookDeliveryRecord {
            val d = EventsWebhookDeliveries
            return WebhookDeliveryRecord(
                id = this[d.id],
                subscriptionId = this[d.webhookId],
                eventSeq = this[d.eventSeq],
                eventType = this[d.eventType],
                payload = this[d.payload],
                state = DeliveryState.parse(this[d.state]),
                attempts = this[d.attempts],
                nextAttemptAt = this[d.nextAttemptAt],
                lastStatus = this[d.lastStatus],
                lastError = this[d.lastError],
                createdAt = this[d.createdAt],
                deliveredAt = this[d.deliveredAt],
                test = this[d.test],
            )
        }
    }
}
