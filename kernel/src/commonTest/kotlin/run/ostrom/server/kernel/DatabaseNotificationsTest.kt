// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel

import run.ostrom.server.kernel.database.InProcessNotifications
import run.ostrom.server.kernel.database.PostgresNotifications
import run.ostrom.server.testing.TestDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** What the replicas tell each other through the database: PostgreSQL's `LISTEN`/`NOTIFY`, or within one process. */
class DatabaseNotificationsTest {
    @Test
    fun withinOneProcessANotificationReachesTheListenersOfItsChannel() = runTest {
        val notifications = InProcessNotifications()
        val heard = mutableListOf<String>()
        val other = mutableListOf<String>()
        backgroundScope.launch { notifications.listen("a") { heard += it } }
        backgroundScope.launch { notifications.listen("b") { other += it } }
        runCurrent()

        notifications.notify("a", "one")
        notifications.notify("a", "two")
        runCurrent()

        assertEquals(listOf("one", "two"), heard)
        assertEquals(emptyList(), other)
    }

    // On the real clock: the listener's connection is established by the database, not by virtual time.
    private fun databaseTest(block: suspend CoroutineScope.(TestDatabase, String, PostgresNotifications) -> Unit) {
        runTest(timeout = 1.minutes) {
            withContext(Dispatchers.Default) {
                val database = TestDatabase.create() ?: return@withContext
                try {
                    database.withRole { role, serving ->
                        val notifications = PostgresNotifications(serving, 50.milliseconds, 50.milliseconds)
                        // The listeners never end by themselves: cancelled whatever the block did.
                        coroutineScope {
                            try {
                                block(database, role, notifications)
                            } finally {
                                coroutineContext.cancelChildren()
                            }
                        }
                    }
                } finally {
                    database.close()
                }
            }
        }
    }

    /** Tells [channel] again until [heard] has [payload]: LISTEN starts asynchronously, and a drop loses some. */
    private suspend fun PostgresNotifications.notifyUntilHeard(
        channel: String,
        payload: String,
        heard: List<String>,
    ) {
        withTimeout(20.seconds) {
            while (payload !in heard) {
                notify(channel, payload)
                delay(100.milliseconds)
            }
        }
    }

    @Test
    fun onPostgresANotificationReachesAListenerOnItsOwnConnection() = databaseTest { _, _, notifications ->
        val heard = CopyOnWriteArrayList<String>()
        val listener = launch { notifications.listen("ostrom_test_channel") { heard += it } }
        notifications.notifyUntilHeard("ostrom_test_channel", "org:id", heard)
        assertEquals("org:id", heard.first())
        listener.cancel()
    }

    @Test
    fun aNotificationOnAnotherChannelIsNotDelivered() = databaseTest { _, _, notifications ->
        val heard = CopyOnWriteArrayList<String>()
        val other = CopyOnWriteArrayList<String>()
        val listener = launch { notifications.listen("ostrom_test_a") { heard += it } }
        val another = launch { notifications.listen("ostrom_test_b") { other += it } }
        notifications.notifyUntilHeard("ostrom_test_b", "for b", other)
        notifications.notifyUntilHeard("ostrom_test_a", "for a", heard)
        assertEquals(listOf("for a"), heard.distinct())
        assertEquals(listOf("for b"), other.distinct())
        listener.cancel()
        another.cancel()
    }

    @Test
    fun aChannelThatIsNotALowerCaseIdentifierIsRefused() = databaseTest { _, _, notifications ->
        assertFailsWith<IllegalArgumentException> { notifications.notify("Bad channel; DROP", "x") }
        assertFailsWith<IllegalArgumentException> { notifications.listen("1abc") {} }
        assertFailsWith<IllegalArgumentException> { notifications.listen("a".repeat(64)) {} }
    }

    @Test
    fun aListenerWhoseConnectionIsKilledListensAgain() = databaseTest { database, role, notifications ->
        val heard = CopyOnWriteArrayList<String>()
        val listener = launch { notifications.listen("ostrom_test_channel") { heard += it } }
        notifications.notifyUntilHeard("ostrom_test_channel", "before", heard)
        val kill = "SELECT CAST(count(pg_terminate_backend(pid)) AS text) FROM pg_stat_activity " +
            "WHERE query LIKE 'LISTEN%' AND usename = '$role'"
        assertEquals("1", database.superuserTexts(kill).single(), "The listener's session is ended")
        notifications.notifyUntilHeard("ostrom_test_channel", "after", heard)
        listener.cancel()
    }
}
