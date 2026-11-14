// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.events.infrastructure

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asBoolean
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstantOrNull
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import io.github.smyrgeorge.sqlx4k.impl.extensions.asIntOrNull
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLongOrNull
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuidOrNull
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
import run.ostrom.server.kernel.database.withOrganization
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [WebhooksRepository] on PostgreSQL (version 2 of [EventsMigrations]). Every call runs in a transaction confined to
 * its organization, except the two router functions. A webhook's row is locked before its deliveries' everywhere
 * (as a delete, which cascades to them, does), so two replicas never deadlock.
 *
 * @param driver The database.
 */
@Suppress("TooManyFunctions") // One per step of the management and of the worker.
class WebhooksRepositoryImpl(private val driver: Driver) : WebhooksRepository {
    override suspend fun list(orgId: Uuid): List<WebhookSubscription> = driver.withOrganization(orgId) { tx ->
        tx.fetchAll(
            Statement.create("$SELECT_WEBHOOK WHERE org_id = :org_id ORDER BY created_at, id LIMIT :limit")
                .bind("org_id", orgId)
                .bind("limit", WebhookRules.MAX_PER_ORG),
        ).getOrThrow().map { it.toWebhook() }
    }

    override suspend fun find(orgId: Uuid, id: Uuid): WebhookSubscription? = driver.withOrganization(orgId) { tx ->
        tx.webhook(orgId, id, lock = false)
    }

    override suspend fun create(subscription: WebhookSubscription, sealedSecret: String): WebhookSubscription {
        val orgId = subscription.orgId
        return driver.withOrganization(orgId) { tx ->
            // The cursor row, locked: creations of the organization take turns (the limit holds), and the webhook
            // starts at the head its publishers cannot move meanwhile.
            val head = tx.fetchAll(Statement.create(LOCK_HEAD_SQL).bind("org_id", orgId)).getOrThrow()
                .first().get("last_seq").asLong()
            val count = tx.fetchAll(
                Statement.create(COUNT_SQL).bind("org_id", orgId),
            ).getOrThrow().first().get("n").asLong()
            if (count >= WebhookRules.MAX_PER_ORG) throw WebhookLimitException()
            tx.fetchAll(
                Statement.create(INSERT_WEBHOOK_SQL)
                    .bind("id", subscription.id)
                    .bind("org_id", orgId)
                    .bind("url", subscription.url)
                    .bind("types", encode(subscription.types))
                    .bind("description", subscription.description)
                    .bind("secret", sealedSecret)
                    .bind("created_by", subscription.createdBy)
                    .bind("at", subscription.createdAt)
                    .bind("fanout_seq", head),
            ).getOrThrow().first().toWebhook()
        }
    }

    override suspend fun update(orgId: Uuid, id: Uuid, change: WebhookChange): WebhookSubscription =
        driver.withOrganization(orgId) { tx ->
            val current = tx.webhook(orgId, id, lock = true) ?: throw WebhookNotFoundException()
            if (current.version != change.version) throw StaleWebhookVersionException()
            val enable = change.enabled ?: current.enabled
            // Enabled again: the events of the time it was off are not replayed, and its failures start over.
            val again = enable && !current.enabled
            val statement = Statement.create(UPDATE_WEBHOOK_SQL)
                .bind("org_id", orgId)
                .bind("id", id)
                .bind("url", change.url)
                .bind("types", encode(change.types))
                .bind("description", change.description)
                .bind("enabled", enable)
                .bindText("disabled_reason", if (enable) null else current.disabledReason ?: PAUSED)
                .bind("at", change.at)
                .bind("fanout_seq", if (again) tx.headOf(orgId) else current.fanoutSeq)
                .bind("failures", if (again) 0 else current.failures)
            tx.fetchAll(statement).getOrThrow().first().toWebhook()
        }

    override suspend fun rotateSecret(orgId: Uuid, id: Uuid, sealedSecret: String, at: Instant): WebhookSubscription =
        driver.withOrganization(orgId) { tx ->
            tx.fetchAll(
                Statement.create(ROTATE_SQL).bind("org_id", orgId).bind("id", id).bind("secret", sealedSecret)
                    .bind("at", at),
            ).getOrThrow().firstOrNull()?.toWebhook() ?: throw WebhookNotFoundException()
        }

    override suspend fun delete(orgId: Uuid, id: Uuid) {
        driver.withOrganization(orgId) { tx ->
            val gone = tx.fetchAll(
                Statement.create("DELETE FROM events_webhooks WHERE org_id = :org_id AND id = :id RETURNING id")
                    .bind("org_id", orgId).bind("id", id),
            ).getOrThrow()
            if (gone.none()) throw WebhookNotFoundException()
        }
    }

    override suspend fun deliveries(orgId: Uuid, id: Uuid, limit: Int): List<WebhookDeliveryRecord> =
        driver.withOrganization(orgId) { tx ->
            tx.fetchAll(
                Statement.create(
                    "$SELECT_DELIVERY WHERE org_id = :org_id AND webhook_id = :id " +
                        "ORDER BY created_at DESC, id DESC LIMIT :limit",
                ).bind("org_id", orgId).bind("id", id).bind("limit", limit),
            ).getOrThrow().map { it.toDelivery() }
        }

    override suspend fun enqueueTest(orgId: Uuid, delivery: WebhookDeliveryRecord): WebhookDeliveryRecord =
        driver.withOrganization(orgId) { tx ->
            tx.webhook(orgId, delivery.subscriptionId, lock = true) ?: throw WebhookNotFoundException()
            // A test is not a pending event: a flood of them is bounded by the rate, under the webhook's lock.
            val recent = tx.fetchAll(
                Statement.create(RECENT_TESTS_SQL)
                    .bind("org_id", orgId)
                    .bind("id", delivery.subscriptionId)
                    .bind("since", delivery.createdAt - WebhookRules.TEST_WINDOW),
            ).getOrThrow().first().get("n").asLong()
            if (recent >= WebhookRules.MAX_TESTS) throw WebhookTestRateException()
            tx.insertDelivery(orgId, delivery).getOrThrow().first().toDelivery()
        }

    override suspend fun workingOrgs(now: Instant, limit: Int): List<Uuid> = driver.fetchAll(
        Statement.create(
            "SELECT org_id FROM events_webhook_orgs(CAST(:now AS TIMESTAMPTZ), CAST(:limit AS INTEGER))",
        ).bind("now", now).bind("limit", limit),
    ).getOrThrow().map { it.get("org_id").asUuid() }

    override suspend fun fanOut(
        orgId: Uuid,
        perWebhook: Int,
        now: Instant,
        payload: (Uuid, JournalEvent) -> String,
    ): Int = driver.withOrganization(orgId) { tx ->
        val head = tx.headOf(orgId)
        // Locked, skipping one another replica is turning into deliveries right now.
        val behind = tx.fetchAll(
            Statement.create(BEHIND_SQL).bind("org_id", orgId).bind("head", head),
        ).getOrThrow().map { Triple(it.get("id").asUuid(), it.get("types").asString(), it.get("fanout_seq").asLong()) }
        var queued = 0
        for ((id, types, from) in behind) {
            val rows = tx.fetchAll(
                Statement.create(EVENTS_SQL)
                    .bind("org_id", orgId)
                    .bind("after", from)
                    .bind("head", head)
                    .bind("types", types)
                    .bind("limit", perWebhook + 1),
            ).getOrThrow().map { it.toJournalEvent() }
            val page = rows.take(perWebhook)
            for (event in page) {
                val deliveryId = Uuid.random()
                val delivery = eventDelivery(deliveryId, id, event, payload(deliveryId, event), now)
                queued += tx.insertDelivery(orgId, delivery).getOrThrow().size
            }
            // A full page resumes after its last event; otherwise everything up to the head was looked at.
            val through = if (rows.size > perWebhook) page.last().seq else head
            tx.execute(
                Statement.create("UPDATE events_webhooks SET fanout_seq = :seq WHERE org_id = :org_id AND id = :id")
                    .bind("seq", through).bind("org_id", orgId).bind("id", id),
            ).getOrThrow()
        }
        queued
    }

    override suspend fun claimDue(orgId: Uuid, limit: Int, now: Instant, lease: Duration): List<DueDelivery> =
        driver.withOrganization(orgId) { tx ->
            tx.fetchAll(
                Statement.create(CLAIM_SQL)
                    .bind("org_id", orgId)
                    .bind("now", now)
                    .bind("until", now + lease)
                    .bind("limit", limit),
            ).getOrThrow().map {
                DueDelivery(orgId, it.toDelivery(), it.get("url").asString(), it.get("secret").asString())
            }
        }

    override suspend fun record(orgId: Uuid, id: Uuid, outcome: DeliveryOutcome) {
        driver.withOrganization(orgId) { tx ->
            // The webhook's row first, as everywhere; a delivery deleted meanwhile (with its webhook) is gone.
            val owner = tx.fetchAll(Statement.create(OWNER_SQL).bind("org_id", orgId).bind("id", id)).getOrThrow()
                .firstOrNull() ?: return@withOrganization
            val webhookId = owner.get("webhook_id").asUuid()
            val result = outcome.result
            tx.execute(
                Statement.create(RECORD_SQL)
                    .bind("org_id", orgId)
                    .bind("id", id)
                    .bind("state", outcome.state.wire)
                    .bind("attempts", outcome.attempts)
                    .bind("next", outcome.nextAttemptAt)
                    .bind("released", outcome.at)
                    .bindInt("last_status", result.status)
                    .bindText("last_error", result.error?.take(MAX_ERROR))
                    .bindInstant("delivered_at", outcome.at.takeIf { outcome.state == DeliveryState.DELIVERED }),
            ).getOrThrow()
            // Tests say nothing of the health of the webhook's real traffic.
            if (owner.get("test").asBoolean() || outcome.state == DeliveryState.PENDING) return@withOrganization
            val failures = if (outcome.state == DeliveryState.DELIVERED) 0 else owner.get("failures").asInt() + 1
            val disable = owner.get("enabled").asBoolean() && failures >= WebhookRules.DISABLE_AFTER
            val counted = Statement.create(if (disable) DISABLE_SQL else FAILURES_SQL)
                .bind("org_id", orgId)
                .bind("id", webhookId)
                .bind("failures", failures)
            tx.execute(if (disable) counted.bind("at", outcome.at) else counted).getOrThrow()
        }
    }

    override suspend fun maintain(now: Instant, giveUpBefore: Instant, deleteBefore: Instant, limit: Int): Int =
        driver.fetchAll(
            Statement.create(
                "SELECT events_webhook_maintain(CAST(:now AS TIMESTAMPTZ), CAST(:give_up AS TIMESTAMPTZ), " +
                    "CAST(:delete_before AS TIMESTAMPTZ), CAST(:limit AS INTEGER)) AS n",
            ).bind("now", now).bind("give_up", giveUpBefore).bind("delete_before", deleteBefore).bind("limit", limit),
        ).getOrThrow().first().get("n").asLong().toInt()

    override suspend fun erase(orgId: Uuid) {
        driver.withOrganization(orgId) { tx ->
            tx.execute(
                Statement.create("DELETE FROM events_webhook_deliveries WHERE org_id = :org_id").bind("org_id", orgId),
            ).getOrThrow()
            tx.execute(Statement.create("DELETE FROM events_webhooks WHERE org_id = :org_id").bind("org_id", orgId))
                .getOrThrow()
        }
    }

    private companion object {
        /** A paused webhook's reason. */
        const val PAUSED = "disabled"

        /** An automatically disabled webhook's reason. */
        const val TOO_MANY_FAILURES = "too_many_failures"

        /** The longest error code kept. */
        const val MAX_ERROR = 40

        const val WEBHOOK_COLUMNS =
            "id, org_id, url, types, description, enabled, disabled_reason, created_by, created_at, updated_at, " +
                "version, fanout_seq, failures"
        const val SELECT_WEBHOOK = "SELECT $WEBHOOK_COLUMNS FROM events_webhooks"
        const val DELIVERY_COLUMNS =
            "id, webhook_id, event_seq, event_type, payload, state, attempts, next_attempt_at, last_status, " +
                "last_error, created_at, delivered_at, test"
        const val SELECT_DELIVERY = "SELECT $DELIVERY_COLUMNS FROM events_webhook_deliveries"
        const val COUNT_SQL = "SELECT COUNT(*) AS n FROM events_webhooks WHERE org_id = :org_id"
        const val HEAD_SQL = "SELECT last_seq FROM events_cursors WHERE org_id = :org_id"
        const val LOCK_HEAD_SQL =
            "INSERT INTO events_cursors (org_id, last_seq) VALUES (:org_id, 0) " +
                "ON CONFLICT (org_id) DO UPDATE SET last_seq = events_cursors.last_seq RETURNING last_seq"
        const val INSERT_WEBHOOK_SQL =
            "INSERT INTO events_webhooks (id, org_id, url, types, description, enabled, disabled_reason, secret, " +
                "created_by, created_at, updated_at, version, fanout_seq, failures) VALUES (:id, :org_id, :url, " +
                ":types, :description, TRUE, NULL, :secret, :created_by, :at, :at, 1, :fanout_seq, 0) " +
                "RETURNING $WEBHOOK_COLUMNS"
        const val UPDATE_WEBHOOK_SQL =
            "UPDATE events_webhooks SET url = :url, types = :types, description = :description, enabled = :enabled, " +
                "disabled_reason = :disabled_reason, updated_at = :at, version = version + 1, " +
                "fanout_seq = :fanout_seq, failures = :failures WHERE org_id = :org_id AND id = :id " +
                "RETURNING $WEBHOOK_COLUMNS"
        const val ROTATE_SQL =
            "UPDATE events_webhooks SET secret = :secret, updated_at = :at, version = version + 1 " +
                "WHERE org_id = :org_id AND id = :id RETURNING $WEBHOOK_COLUMNS"
        const val RECENT_TESTS_SQL =
            "SELECT COUNT(*) AS n FROM events_webhook_deliveries WHERE org_id = :org_id AND webhook_id = :id " +
                "AND test AND created_at > :since"
        const val INSERT_DELIVERY_SQL =
            "INSERT INTO events_webhook_deliveries (id, org_id, webhook_id, event_seq, event_type, payload, state, " +
                "attempts, next_attempt_at, lease_until, last_status, last_error, created_at, delivered_at, test) " +
                "VALUES (:id, :org_id, :webhook_id, :event_seq, :event_type, :payload, :state, 0, :at, :at, NULL, " +
                "NULL, :at, NULL, :test) ON CONFLICT (webhook_id, event_seq) DO NOTHING RETURNING $DELIVERY_COLUMNS"
        const val BEHIND_SQL =
            "SELECT id, types, fanout_seq FROM events_webhooks WHERE org_id = :org_id AND enabled " +
                "AND fanout_seq < :head ORDER BY id FOR UPDATE SKIP LOCKED"
        const val EVENTS_SQL =
            "SELECT seq, type, subject, subject_id, member_id, at, data FROM events_journal " +
                "WHERE org_id = :org_id AND seq > :after AND seq <= :head " +
                "AND type = ANY(string_to_array(CAST(:types AS TEXT), ',')) ORDER BY seq LIMIT :limit"
        const val CLAIM_SQL =
            "UPDATE events_webhook_deliveries d SET lease_until = :until FROM events_webhooks w " +
                "WHERE w.id = d.webhook_id AND d.org_id = :org_id AND d.id IN (" +
                "SELECT e.id FROM events_webhook_deliveries e JOIN events_webhooks x ON x.id = e.webhook_id " +
                "WHERE e.org_id = :org_id AND e.state = 'pending' AND e.next_attempt_at <= :now " +
                "AND e.lease_until <= :now AND (x.enabled OR e.test) ORDER BY e.next_attempt_at LIMIT :limit " +
                "FOR UPDATE OF e SKIP LOCKED) " +
                "RETURNING d.id, d.webhook_id, d.event_seq, d.event_type, d.payload, d.state, d.attempts, " +
                "d.next_attempt_at, d.last_status, d.last_error, d.created_at, d.delivered_at, d.test, w.url, w.secret"
        const val OWNER_SQL =
            "SELECT d.webhook_id, d.test, w.failures, w.enabled FROM events_webhook_deliveries d " +
                "JOIN events_webhooks w ON w.id = d.webhook_id WHERE d.org_id = :org_id AND d.id = :id FOR UPDATE OF w"
        const val RECORD_SQL =
            "UPDATE events_webhook_deliveries SET state = :state, attempts = :attempts, next_attempt_at = :next, " +
                "lease_until = :released, last_status = :last_status, last_error = :last_error, " +
                "delivered_at = :delivered_at WHERE org_id = :org_id AND id = :id"
        const val FAILURES_SQL =
            "UPDATE events_webhooks SET failures = :failures WHERE org_id = :org_id AND id = :id"
        const val DISABLE_SQL =
            "UPDATE events_webhooks SET failures = :failures, enabled = FALSE, " +
                "disabled_reason = '$TOO_MANY_FAILURES', updated_at = :at, version = version + 1 " +
                "WHERE org_id = :org_id AND id = :id"

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

        suspend fun QueryExecutor.headOf(orgId: Uuid): Long =
            fetchAll(Statement.create(HEAD_SQL).bind("org_id", orgId)).getOrThrow().firstOrNull()
                ?.get("last_seq")?.asLong() ?: 0L

        suspend fun QueryExecutor.webhook(orgId: Uuid, id: Uuid, lock: Boolean): WebhookSubscription? = fetchAll(
            Statement.create("$SELECT_WEBHOOK WHERE org_id = :org_id AND id = :id${if (lock) " FOR UPDATE" else ""}")
                .bind("org_id", orgId)
                .bind("id", id),
        ).getOrThrow().firstOrNull()?.toWebhook()

        suspend fun QueryExecutor.insertDelivery(orgId: Uuid, delivery: WebhookDeliveryRecord) = fetchAll(
            Statement.create(INSERT_DELIVERY_SQL)
                .bind("id", delivery.id)
                .bind("org_id", orgId)
                .bind("webhook_id", delivery.subscriptionId)
                .bindLong("event_seq", delivery.eventSeq)
                .bind("event_type", delivery.eventType)
                .bind("payload", delivery.payload)
                .bind("state", delivery.state.wire)
                .bind("at", delivery.createdAt)
                .bind("test", delivery.test),
        )

        fun Statement.bindText(name: String, value: String?): Statement =
            if (value == null) bindNull(name, String::class) else bind(name, value)

        fun Statement.bindInt(name: String, value: Int?): Statement =
            if (value == null) bindNull(name, Int::class) else bind(name, value)

        fun Statement.bindLong(name: String, value: Long?): Statement =
            if (value == null) bindNull(name, Long::class) else bind(name, value)

        fun Statement.bindInstant(name: String, value: Instant?): Statement =
            if (value == null) bindNull(name, Instant::class) else bind(name, value)

        fun ResultSet.Row.toWebhook() = WebhookSubscription(
            id = get("id").asUuid(),
            orgId = get("org_id").asUuid(),
            url = get("url").asString(),
            types = get("types").asString().split(',').filter { it.isNotEmpty() }.toSet(),
            description = get("description").asString(),
            enabled = get("enabled").asBoolean(),
            disabledReason = get("disabled_reason").asStringOrNull(),
            createdBy = get("created_by").asUuid(),
            createdAt = get("created_at").asInstant(),
            updatedAt = get("updated_at").asInstant(),
            version = get("version").asInt(),
            fanoutSeq = get("fanout_seq").asLong(),
            failures = get("failures").asInt(),
        )

        fun ResultSet.Row.toDelivery() = WebhookDeliveryRecord(
            id = get("id").asUuid(),
            subscriptionId = get("webhook_id").asUuid(),
            eventSeq = get("event_seq").asLongOrNull(),
            eventType = get("event_type").asString(),
            payload = get("payload").asString(),
            state = DeliveryState.parse(get("state").asString()),
            attempts = get("attempts").asInt(),
            nextAttemptAt = get("next_attempt_at").asInstant(),
            lastStatus = get("last_status").asIntOrNull(),
            lastError = get("last_error").asStringOrNull(),
            createdAt = get("created_at").asInstant(),
            deliveredAt = get("delivered_at").asInstantOrNull(),
            test = get("test").asBoolean(),
        )

        fun ResultSet.Row.toJournalEvent() = JournalEvent(
            seq = get("seq").asLong(),
            type = get("type").asString(),
            subject = get("subject").asString(),
            subjectId = get("subject_id").asString(),
            memberId = get("member_id").asUuidOrNull(),
            at = get("at").asInstant(),
            data = get("data").asString(),
        )
    }
}
