// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.usage

import run.ostrom.server.kernel.database.DatabaseNotifications
import run.ostrom.server.kernel.database.InProcessNotifications
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.usage.domain.UsageEvent
import run.ostrom.server.usage.infrastructure.ReplicatedUsageFeed
import run.ostrom.server.usage.infrastructure.UsageFeedImpl
import run.ostrom.server.usage.infrastructure.UsageFeedReads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * The live feed across replicas (TC-REP-1), two replicas on one database's notifications: an event recorded on one
 * reaches the other's subscribers, read from the ledger by id; nothing is read where nobody watches.
 */
class ReplicatedUsageFeedTest {
    private val ledger = mutableMapOf<Uuid, UsageEvent>()
    private val read = mutableListOf<Pair<Uuid, List<Uuid>>>()
    private val reads = UsageFeedReads { org, ids ->
        read += org to ids
        ids.mapNotNull { ledger[it]?.takeIf { event -> event.orgId == org } }
    }
    private val logger = RecordingLogger()

    private fun replica(notifications: DatabaseNotifications, reads: UsageFeedReads = this.reads) =
        ReplicatedUsageFeed(UsageFeedImpl(), notifications, reads, logger)

    private fun CoroutineScope.follow(feed: ReplicatedUsageFeed, org: Uuid): MutableList<UsageEvent> {
        val received = mutableListOf<UsageEvent>()
        launch { feed.subscribe(org).toList(received) }
        return received
    }

    private fun TestScope.recorded(feed: ReplicatedUsageFeed, event: UsageEvent) {
        ledger[event.id] = event
        launch { feed.publish(event) }
        runCurrent()
    }

    @Test
    fun anEventRecordedOnOneReplicaReachesTheOthersSubscribersOnce() = runTest {
        val database = InProcessNotifications()
        val (a, b) = replica(database) to replica(database)
        backgroundScope.launch { a.run() }
        backgroundScope.launch { b.run() }
        val onA = backgroundScope.follow(a, TestCallers.orgA)
        val onB = backgroundScope.follow(b, TestCallers.orgA)
        val otherOrgOnB = backgroundScope.follow(b, TestCallers.orgB)
        runCurrent()

        val event = modelEvent(42)
        recorded(a, event)

        assertEquals(listOf(event), onA, "Once on its own replica: it skips what it told itself")
        assertEquals(listOf(event), onB, "Read from the ledger on the other one")
        assertEquals(emptyList(), otherOrgOnB)
        assertEquals(listOf(TestCallers.orgA to listOf(event.id)), read, "Read inside its org, by id")
    }

    @Test
    fun aReplicaWhereNobodyWatchesTheOrgNowReadsNothing() = runTest {
        val database = InProcessNotifications()
        val (a, b) = replica(database) to replica(database)
        backgroundScope.launch { a.run() }
        backgroundScope.launch { b.run() }
        backgroundScope.follow(b, TestCallers.orgB)
        val left = launch { b.subscribe(TestCallers.orgA).collect {} }
        runCurrent()
        left.cancel()
        runCurrent()

        recorded(a, modelEvent(1))

        assertEquals(emptyList(), read)
    }

    @Test
    fun eventsAreToldInBatchesInTheirOrder() = runTest {
        val sent = mutableListOf<String>()
        val database = InProcessNotifications()
        val counting = object : DatabaseNotifications by database {
            override suspend fun notify(channel: String, payload: String) {
                sent += payload
                database.notify(channel, payload)
            }
        }
        val a = replica(counting)
        val b = replica(database)
        val onB = backgroundScope.follow(b, TestCallers.orgA)
        backgroundScope.launch { b.run() }
        runCurrent()
        val events = List(70) { modelEvent(it.toLong()) }
        events.forEach { ledger[it.id] = it }
        launch { events.forEach { a.publish(it) } }
        runCurrent()
        backgroundScope.launch { a.run() }
        runCurrent()

        assertEquals(listOf(64, 6), sent.map { it.split(' ').size - 2 }, "Up to 64 events per notification")
        assertEquals(events, onB)
    }

    @Test
    fun aMalformedOrUnknownNotificationIsIgnored() = runTest {
        val database = InProcessNotifications()
        val b = replica(database)
        backgroundScope.launch { b.run() }
        val onB = backgroundScope.follow(b, TestCallers.orgA)
        runCurrent()
        val other = Uuid.random()
        val unknown = Uuid.random()
        listOf(
            "garbage",
            "v1 $other",
            "v2 $other ${TestCallers.orgA}:$unknown",
            "v1 $other not-an-org:$unknown ${TestCallers.orgA}:not-an-id ${TestCallers.orgA}",
            "v1 $other ${TestCallers.orgA}:$unknown",
        ).forEach { launch { database.notify(ReplicatedUsageFeed.CHANNEL, it) } }
        runCurrent()

        assertEquals(emptyList(), onB)
        assertEquals(listOf(TestCallers.orgA to listOf(unknown)), read, "Only the well-formed one is read")
    }

    @Test
    fun aFailedNotificationReadOrListenerIsLoggedAndTheFeedGoesOn() = runTest {
        val database = InProcessNotifications()
        var listens = 0
        val flaky = object : DatabaseNotifications {
            override suspend fun notify(channel: String, payload: String) = error("database down")

            override suspend fun listen(channel: String, onPayload: suspend (String) -> Unit) {
                listens++
                if (listens == 1) error("database down")
                database.listen(channel, onPayload)
            }
        }
        val a = replica(flaky, reads = { _, _ -> error("database down") })
        backgroundScope.launch { a.run() }
        val onA = backgroundScope.follow(a, TestCallers.orgA)
        runCurrent()
        val event = modelEvent(7)
        recorded(a, event)
        advanceTimeBy(5.seconds + 1.seconds)
        launch { database.notify(ReplicatedUsageFeed.CHANNEL, "v1 ${Uuid.random()} ${TestCallers.orgA}:${event.id}") }
        runCurrent()

        assertEquals(listOf(event), onA, "This replica's subscribers still see its own events")
        assertEquals(2, listens, "The listener starts again after a pause")
        assertTrue(
            logger.events.containsAll(
                listOf(
                    LogLevel.WARN to "usage feed listener failed",
                    LogLevel.WARN to "usage feed notification failed",
                    LogLevel.WARN to "usage feed read failed",
                ),
            ),
            logger.events.toString(),
        )
    }
}
