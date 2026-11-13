// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel

import run.ostrom.server.kernel.database.InProcessNotifications
import run.ostrom.server.kernel.database.PostgresNotifications
import run.ostrom.server.kernel.database.databaseNotifications
import run.ostrom.server.testing.RecordingDriver
import run.ostrom.server.testing.TestDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
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

    @Test
    fun aDriverThatIsNotAPostgresPoolIsToldWithinTheProcess() = runTest {
        withContext(Dispatchers.Default) {
            val database = TestDatabase.create() ?: return@withContext
            try {
                assertIs<PostgresNotifications>(databaseNotifications(database.driver))
                assertIs<InProcessNotifications>(databaseNotifications(RecordingDriver(database.driver)))
            } finally {
                database.close()
            }
        }
    }

    @Test
    fun onPostgresANotificationReachesAListenerOnItsOwnConnection() = runTest {
        // On the real clock: the listener's connection is established by the database, not by virtual time.
        withContext(Dispatchers.Default) {
            val database = TestDatabase.create() ?: return@withContext
            try {
                val notifications = databaseNotifications(database.driver)
                val heard = CompletableDeferred<String>()
                val listener = launch { notifications.listen("ostrom_test_channel") { heard.complete(it) } }
                withTimeout(20.seconds) {
                    // LISTEN starts asynchronously: told again until it is heard.
                    while (!heard.isCompleted) {
                        notifications.notify("ostrom_test_channel", "org:id")
                        delay(100.milliseconds)
                    }
                }
                assertEquals("org:id", heard.await())
                listener.cancel()
            } finally {
                database.close()
            }
        }
    }
}
