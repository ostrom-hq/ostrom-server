// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.usage.infrastructure

import run.ostrom.server.kernel.database.DatabaseNotifications
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.secrets.infrastructure.fetchInOrg
import run.ostrom.server.usage.domain.UsageEvent
import run.ostrom.server.usage.domain.UsageFeed
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** Reads recorded events of one org, by id, for the feed of another replica. */
fun interface UsageFeedReads {
    /**
     * Reads them inside the org.
     *
     * @param orgId The org.
     * @param ids The events.
     *
     * @return Those recorded, in no particular order.
     */
    suspend fun read(orgId: Uuid, ids: List<Uuid>): List<UsageEvent>
}

/**
 * [UsageFeedReads] on the ledger, under the org's row-level security.
 *
 * @param driver The database.
 */
class UsageFeedReadsImpl(private val driver: Driver) : UsageFeedReads {
    override suspend fun read(orgId: Uuid, ids: List<Uuid>): List<UsageEvent> {
        val statement = Statement.create(usageFeedReadSql(ids.size)).bind("org_id", orgId)
        ids.forEachIndexed { index, id -> statement.bind("id$index", id) }
        return driver.fetchInOrg(orgId, statement).getOrThrow().map { it.toEvent() }
    }
}

/**
 * The events of one org among [count] ids (`:id0` … ), by their primary key.
 *
 * @param count The ids bound.
 *
 * @return The statement's text.
 */
internal fun usageFeedReadSql(count: Int): String =
    "SELECT $COLUMNS FROM usage_events WHERE org_id = :org_id AND id IN (" +
        (0 until count).joinToString(", ") { ":id$it" } + ")"

/**
 * [UsageFeed] across replicas (TC-REP-1): an event published here reaches this replica's subscribers at once and the
 * other replicas' through PostgreSQL's `NOTIFY` ([DatabaseNotifications]), on the database they already share. A
 * notification names events only (`<org>:<id>`, batched up to [MAX_PER_NOTIFICATION]), never what they hold: a
 * replica with a subscriber of that org reads them from the ledger, inside the org, and one without reads nothing.
 *
 * Best effort, as the feed always was: a notification sent while a replica's listener reconnects is lost to it, and
 * past [OUTGOING] events waiting to be told, the oldest are dropped; the ledger stays complete.
 *
 * @param local This replica's feed.
 * @param notifications The database's notifications.
 * @param reads Reads the events another replica told of.
 * @param logger Where a failed notification or read is reported.
 * @param replica This replica, so that it skips what it told itself.
 * @param retryAfter The pause before listening again after the listener failed.
 */
class ReplicatedUsageFeed(
    private val local: UsageFeedImpl,
    private val notifications: DatabaseNotifications,
    private val reads: UsageFeedReads,
    private val logger: Logger,
    private val replica: Uuid = Uuid.random(),
    private val retryAfter: Duration = 5.seconds,
) : UsageFeed {
    private val outgoing = Channel<UsageEvent>(OUTGOING, BufferOverflow.DROP_OLDEST)

    override suspend fun publish(event: UsageEvent) {
        local.publish(event)
        outgoing.trySend(event)
    }

    override suspend fun subscribe(orgId: Uuid): SharedFlow<UsageEvent> = local.subscribe(orgId)

    /** Tells the other replicas of this one's events and listens to theirs, until cancelled. */
    suspend fun run(): Unit = coroutineScope {
        launch { tell() }
        while (true) {
            quietly("usage feed listener failed") { notifications.listen(CHANNEL, ::heard) }
            delay(retryAfter)
        }
    }

    private suspend fun tell() {
        // The queue is never closed: this runs until cancelled.
        while (true) {
            val batch = mutableListOf(outgoing.receive())
            while (batch.size < MAX_PER_NOTIFICATION) batch += outgoing.tryReceive().getOrNull() ?: break
            val payload = (listOf(VERSION, replica.toString()) + batch.map { "${it.orgId}:${it.id}" }).joinToString(" ")
            quietly("usage feed notification failed") { notifications.notify(CHANNEL, payload) }
        }
    }

    private suspend fun heard(payload: String) {
        val parts = payload.split(' ')
        if (parts.size <= HEADER || parts[0] != VERSION || parts[1] == replica.toString()) return
        val named = parts.drop(HEADER).mapNotNull { named ->
            val org = Uuid.parseOrNull(named.substringBefore(':'))
            val id = Uuid.parseOrNull(named.substringAfter(':', ""))
            if (org == null || id == null) null else org to id
        }
        named.groupBy({ it.first }, { it.second }).forEach { (org, ids) ->
            if (local.watched(org)) {
                quietly("usage feed read failed") {
                    val read = reads.read(org, ids).associateBy { it.id }
                    ids.mapNotNull(read::get).forEach { local.publish(it) }
                }
            }
        }
    }

    private suspend fun quietly(failure: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // The class only, never the message: PostgreSQL quotes row values in it.
            logger.warn(failure, mapOf("error" to error::class.simpleName))
        }
    }

    /** The channel and its limits. */
    companion object {
        /** The notification channel of the usage feed. */
        const val CHANNEL = "ostrom_usage_feed"

        /** Events named per notification: about 75 bytes each, well under PostgreSQL's 8000. */
        const val MAX_PER_NOTIFICATION = 64

        /** Events waiting to be told before the oldest are dropped. */
        const val OUTGOING = 4096

        private const val VERSION = "v1"

        /** The version and the replica, before the events named. */
        private const val HEADER = 2
    }
}
