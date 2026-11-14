// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications

import io.github.smyrgeorge.sqlx4k.Transaction
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import run.ostrom.server.kernel.events.JournalEntry
import run.ostrom.server.kernel.events.JournalPosition
import run.ostrom.server.kernel.events.JournalSlice
import run.ostrom.server.kernel.events.OrgEventJournal
import run.ostrom.server.kernel.events.OrgEventType
import run.ostrom.server.notifications.domain.ChannelResult
import run.ostrom.server.notifications.domain.DeliveryOutcome
import run.ostrom.server.notifications.domain.Notification
import run.ostrom.server.notifications.domain.NotificationChannel
import run.ostrom.server.notifications.domain.NotificationDeliveriesRepository
import run.ostrom.server.notifications.domain.NotificationDelivery
import run.ostrom.server.notifications.domain.NotificationKind
import run.ostrom.server.notifications.domain.NotificationSetting
import run.ostrom.server.notifications.domain.NotificationSettingsRepository
import run.ostrom.server.notifications.domain.SwitchedOff
import run.ostrom.server.secrets.domain.SecretCipher
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** A `cloud.wait.created` event of the journal naming [waitId]. */
fun waitCreated(seq: Long, waitId: String, at: Instant = Instant.parse("2026-11-03T08:00:00Z")) = JournalEntry(
    seq,
    OrgEventType.WAIT_CREATED,
    waitId,
    null,
    at,
    JsonObject(mapOf("waitId" to JsonPrimitive(waitId))),
)

/**
 * A journal whose organizations stand at [positions] and whose pages are [slices]; the cursors moved are recorded.
 *
 * @param positions What [behind] answers.
 * @param slices Each organization's page.
 */
class FakeJournal(
    var positions: List<JournalPosition> = emptyList(),
    var slices: Map<Uuid, JournalSlice> = emptyMap(),
) : OrgEventJournal {
    val read = mutableListOf<Pair<JournalPosition, Set<OrgEventType>>>()
    val advanced = mutableListOf<Pair<JournalPosition, Long>>()

    /** Whether a move succeeds. */
    var moves: Boolean = true

    override suspend fun behind(reader: String, limit: Int): List<JournalPosition> = positions

    override suspend fun read(position: JournalPosition, types: Set<OrgEventType>, limit: Int): JournalSlice {
        read += position to types
        return slices.getValue(position.orgId)
    }

    override suspend fun advanceIn(
        transaction: Transaction,
        reader: String,
        position: JournalPosition,
        through: Long,
    ): Boolean = error("The router moves its cursor on Exposed")

    override fun advanceIn(
        transaction: JdbcTransaction,
        reader: String,
        position: JournalPosition,
        through: Long,
    ): Boolean {
        if (moves) advanced += position to through
        return moves
    }
}

/** A queue in memory: what was enqueued, the rows a claim returns, and what each attempt recorded. */
class MemoryDeliveries : NotificationDeliveriesRepository {
    val enqueued = mutableListOf<Pair<JournalPosition, List<NotificationDelivery>>>()
    val due = mutableMapOf<Uuid, MutableList<NotificationDelivery>>()
    val recorded = mutableMapOf<Uuid, DeliveryOutcome>()
    val maintained = mutableListOf<List<Instant>>()
    val deleted = mutableListOf<Uuid>()

    /** The organizations refusing their page (another replica queued it). */
    var refused: Set<Uuid> = emptySet()

    /** The organizations whose claim fails. */
    var failingClaims: Set<Uuid> = emptySet()

    /** The deliveries whose record fails. */
    var failingRecords: Set<Uuid> = emptySet()

    override suspend fun enqueue(
        position: JournalPosition,
        through: Long,
        deliveries: List<NotificationDelivery>,
    ): Boolean {
        if (position.orgId in refused) return false
        enqueued += position to deliveries
        return true
    }

    override suspend fun workingOrgs(now: Instant, limit: Int): List<Uuid> = due.keys.toList()

    override suspend fun claimDue(
        orgId: Uuid,
        limit: Int,
        now: Instant,
        lease: Duration,
    ): List<NotificationDelivery> {
        check(orgId !in failingClaims) { "The database is down" }
        val rows = due.getValue(orgId)
        val claimed = rows.take(limit)
        rows.removeAll(claimed)
        return claimed
    }

    override suspend fun record(orgId: Uuid, id: Uuid, outcome: DeliveryOutcome, now: Instant) {
        check(id !in failingRecords) { "The database is down" }
        recorded[id] = outcome
    }

    override suspend fun maintain(now: Instant, giveUpBefore: Instant, deleteBefore: Instant, limit: Int): Long {
        maintained += listOf(now, giveUpBefore, deleteBefore)
        return 3
    }

    override suspend fun deleteAll(orgId: Uuid) {
        deleted += orgId
    }
}

/**
 * A channel that reaches [reach] and answers each batch with [answer].
 *
 * @param id Its id.
 * @param reach The members it reaches.
 * @param answer The results of a batch.
 */
class FakeChannel(
    override val id: String,
    private val reach: Set<Uuid> = emptySet(),
    private val answer: suspend (List<Notification>) -> Map<Uuid, ChannelResult> = { batch ->
        batch.associate { it.id to ChannelResult.DELIVERED }
    },
) : NotificationChannel {
    val batches = mutableListOf<List<Notification>>()

    override suspend fun reachable(orgId: Uuid, memberIds: Set<Uuid>): Set<Uuid> = memberIds intersect reach

    override suspend fun deliver(notifications: List<Notification>): Map<Uuid, ChannelResult> {
        batches += notifications
        return answer(notifications)
    }
}

/**
 * The members' switches, with [off] turned off.
 *
 * @param off The switches turned off.
 */
class OffSettings(private val off: Set<SwitchedOff> = emptySet()) : NotificationSettingsRepository {
    val asked = mutableListOf<Set<Uuid>>()

    override suspend fun settings(orgId: Uuid, memberId: Uuid): List<NotificationSetting> = emptyList()

    override suspend fun set(orgId: Uuid, memberId: Uuid, setting: NotificationSetting) = Unit

    override suspend fun disabled(orgId: Uuid, memberIds: Set<Uuid>): Set<SwitchedOff> {
        asked += memberIds
        return off
    }

    override suspend fun deleteAll(orgId: Uuid) = Unit
}

/** A sealed secret is its plaintext, under its context: the events module's cipher in a test. */
class PlainCipher : SecretCipher {
    override suspend fun seal(plaintext: ByteArray, context: String): String = "$context:${plaintext.decodeToString()}"

    override suspend fun open(envelope: String, context: String): ByteArray? =
        envelope.removePrefix("$context:").encodeToByteArray()
}

/**
 * A notification of [orgId] about [waitId].
 *
 * @param orgId The organization.
 * @param waitId The wait.
 * @param member The member told.
 */
fun notification(orgId: Uuid, waitId: String?, member: Uuid = Uuid.random()) = Notification(
    id = Uuid.random(),
    orgId = orgId,
    memberId = member,
    kind = NotificationKind.WAITING,
    title = "Ostrom",
    body = "Deploy?",
    sessionId = "s-1",
    waitId = waitId,
    collapseKey = "wait:$waitId",
    createdAt = Instant.parse("2026-11-03T08:00:00Z"),
    eventSeq = 1,
)
