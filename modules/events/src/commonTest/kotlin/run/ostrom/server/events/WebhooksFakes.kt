// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.events

import run.ostrom.server.events.domain.DeliveryOutcome
import run.ostrom.server.events.domain.DueDelivery
import run.ostrom.server.events.domain.JournalEvent
import run.ostrom.server.events.domain.StaleWebhookVersionException
import run.ostrom.server.events.domain.WebhookChange
import run.ostrom.server.events.domain.WebhookDeliveryRecord
import run.ostrom.server.events.domain.WebhookLimitException
import run.ostrom.server.events.domain.WebhookNotFoundException
import run.ostrom.server.events.domain.WebhookRules
import run.ostrom.server.events.domain.WebhookSigner
import run.ostrom.server.events.domain.WebhookSubscription
import run.ostrom.server.events.domain.WebhookTestRateException
import run.ostrom.server.events.domain.WebhooksRepository
import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.secrets.domain.SecretCipher
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Seals as `sealed(<context>):<text>`, and opens only in the same context. */
class ContextCipher : SecretCipher {
    override suspend fun seal(plaintext: ByteArray, context: String): String =
        "sealed($context):${plaintext.decodeToString()}"

    override suspend fun open(envelope: String, context: String): ByteArray? =
        envelope.removePrefix("sealed($context):").takeIf { it != envelope }?.encodeToByteArray()
}

/** Signs as `v1=<secret>|<timestamp>|<body length>` and makes numbered secrets. */
class PlainSigner : WebhookSigner {
    private var made = 0

    override suspend fun signature(secret: String, timestampSeconds: Long, body: String): String =
        "v1=$secret|$timestampSeconds|${body.length}"

    override fun newSecret(): String = "whsec_${++made}"
}

/** Keeps the audit records. */
class RecordingTrail : OrgAuditTrail {
    val records = mutableListOf<OrgAuditRecord>()

    override suspend fun record(record: OrgAuditRecord) {
        records += record
    }
}

/** A clock the test moves by hand. */
class MovingClock(var now: Instant = Instant.parse("2026-11-13T10:00:00Z")) : Clock {
    override fun now(): Instant = now
}

/** The webhooks in memory: what the use cases ask and what they are answered. */
class InMemoryWebhooks : WebhooksRepository {
    val webhooks = mutableMapOf<Uuid, WebhookSubscription>()
    val secrets = mutableMapOf<Uuid, String>()
    val tests = mutableListOf<WebhookDeliveryRecord>()
    val updates = mutableListOf<WebhookChange>()
    var orgs = listOf<Uuid>()
    val fannedOut = mutableListOf<Uuid>()
    var events = listOf<JournalEvent>()
    val payloads = mutableListOf<String>()
    val due = mutableMapOf<Uuid, MutableList<DueDelivery>>()
    val claims = mutableListOf<Pair<Uuid, Int>>()
    val recorded = mutableListOf<Pair<Uuid, DeliveryOutcome>>()
    val maintained = mutableListOf<List<Instant>>()
    val erased = mutableListOf<Uuid>()

    override suspend fun list(orgId: Uuid): List<WebhookSubscription> = webhooks.values.filter { it.orgId == orgId }

    override suspend fun find(orgId: Uuid, id: Uuid): WebhookSubscription? = webhooks[id]?.takeIf { it.orgId == orgId }

    override suspend fun create(subscription: WebhookSubscription, sealedSecret: String): WebhookSubscription {
        if (list(subscription.orgId).size >= WebhookRules.MAX_PER_ORG) throw WebhookLimitException()
        webhooks[subscription.id] = subscription
        secrets[subscription.id] = sealedSecret
        return subscription
    }

    override suspend fun update(orgId: Uuid, id: Uuid, change: WebhookChange): WebhookSubscription {
        val current = find(orgId, id) ?: throw WebhookNotFoundException()
        if (current.version != change.version) throw StaleWebhookVersionException()
        updates += change
        val changed = current.copy(
            url = change.url,
            types = change.types,
            description = change.description,
            enabled = change.enabled ?: current.enabled,
            version = current.version + 1,
        )
        webhooks[id] = changed
        return changed
    }

    override suspend fun rotateSecret(orgId: Uuid, id: Uuid, sealedSecret: String, at: Instant): WebhookSubscription {
        val current = find(orgId, id) ?: throw WebhookNotFoundException()
        secrets[id] = sealedSecret
        return current.copy(version = current.version + 1).also { webhooks[id] = it }
    }

    override suspend fun delete(orgId: Uuid, id: Uuid) {
        find(orgId, id) ?: throw WebhookNotFoundException()
        webhooks.remove(id)
    }

    override suspend fun deliveries(orgId: Uuid, id: Uuid, limit: Int): List<WebhookDeliveryRecord> =
        tests.filter { it.subscriptionId == id }.take(limit)

    override suspend fun enqueueTest(orgId: Uuid, delivery: WebhookDeliveryRecord): WebhookDeliveryRecord {
        find(orgId, delivery.subscriptionId) ?: throw WebhookNotFoundException()
        if (tests.count { it.subscriptionId == delivery.subscriptionId } >= WebhookRules.MAX_TESTS) {
            throw WebhookTestRateException()
        }
        tests += delivery
        return delivery
    }

    override suspend fun workingOrgs(now: Instant, limit: Int): List<Uuid> = orgs.take(limit)

    override suspend fun fanOut(
        orgId: Uuid,
        perWebhook: Int,
        now: Instant,
        payload: (Uuid, JournalEvent) -> String,
    ): Int {
        fannedOut += orgId
        events.forEach { payloads += payload(Uuid.parse("00000000-0000-0000-0000-00000000000d"), it) }
        return events.size
    }

    override suspend fun claimDue(orgId: Uuid, limit: Int, now: Instant, lease: Duration): List<DueDelivery> {
        claims += orgId to limit
        return due[orgId].orEmpty().take(limit)
    }

    override suspend fun record(orgId: Uuid, id: Uuid, outcome: DeliveryOutcome) {
        recorded += id to outcome
    }

    override suspend fun maintain(now: Instant, giveUpBefore: Instant, deleteBefore: Instant, limit: Int): Int {
        maintained += listOf(now, giveUpBefore, deleteBefore)
        return limit
    }

    override suspend fun erase(orgId: Uuid) {
        erased += orgId
    }
}
