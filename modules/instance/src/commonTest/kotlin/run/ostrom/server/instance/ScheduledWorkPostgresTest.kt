// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.instance

import run.ostrom.server.instance.infrastructure.ScheduledWorkImpl
import run.ostrom.server.kernel.database.ScheduledWork
import run.ostrom.server.testing.serverTest
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The leases of periodic work on a real PostgreSQL (TeamCode's `SqlScheduledWork`): a job runs on one replica at a
 * time and once per interval, whatever the number of callers, and a run that fails or overruns gives the lease back.
 */
class ScheduledWorkPostgresTest {
    private fun leaseTest(block: suspend (ScheduledWork, () -> ScheduledWork) -> Unit) =
        serverTest(listOf(InstanceModule)) { server ->
            block(server.koin.get<ScheduledWork>()) { ScheduledWorkImpl(server.driver) }
        }

    @Test
    fun aDueJobRunsThenWaitsForItsInterval() = leaseTest { work, _ ->
        var runs = 0
        assertTrue(work.runDue("memory/settle", interval = 1.hours, timeout = 1.seconds) { runs++ })
        assertFalse(work.runDue("memory/settle", interval = 1.hours, timeout = 1.seconds) { runs++ })
        assertEquals(1, runs)
    }

    @Test
    fun twoReplicasRacingForTheSameJobRunItOnce() = leaseTest { _, replica ->
        val first = replica()
        val second = replica()
        var runs = 0
        val ran = coroutineScope {
            listOf(first, second).map { work ->
                async {
                    work.runDue("memory/curate", interval = 1.hours, timeout = 1.seconds) {
                        runs++
                        delay(300.milliseconds)
                    }
                }
            }.awaitAll()
        }
        assertEquals(1, ran.count { it })
        assertEquals(1, runs)
    }

    @Test
    fun aJobRunsAgainOnceItsIntervalIsOver() = leaseTest { work, replica ->
        var runs = 0
        assertTrue(work.runDue("mcp/retain", interval = 50.milliseconds, timeout = 1.seconds) { runs++ })
        delay(200.milliseconds)
        assertTrue(replica().runDue("mcp/retain", interval = 50.milliseconds, timeout = 1.seconds) { runs++ })
        assertEquals(2, runs)
    }

    @Test
    fun jobsOfDifferentNamesDoNotWaitForEachOther() = leaseTest { work, _ ->
        assertTrue(work.runDue("a", interval = 1.hours, timeout = 1.seconds) {})
        assertTrue(work.runDue("b", interval = 1.hours, timeout = 1.seconds) {})
    }

    @Test
    fun aFailingRunGivesTheLeaseBackAndFails() = leaseTest { work, _ ->
        assertFailsWith<IllegalStateException> {
            work.runDue("broken", interval = 50.milliseconds, timeout = 1.seconds) { error("boom") }
        }
        delay(200.milliseconds)
        assertTrue(work.runDue("broken", interval = 50.milliseconds, timeout = 1.seconds) {})
    }

    @Test
    fun aRunPastItsTimeoutIsCancelledAndGivesTheLeaseBack() = leaseTest { work, _ ->
        assertFailsWith<TimeoutCancellationException> {
            work.runDue("slow", interval = 50.milliseconds, timeout = 50.milliseconds) { delay(10.seconds) }
        }
        delay(200.milliseconds)
        assertTrue(work.runDue("slow", interval = 50.milliseconds, timeout = 1.seconds) {})
    }

    @Test
    fun aNameOrADurationOutOfItsBoundsIsRefused() = leaseTest { work, _ ->
        suspend fun refused(name: String, interval: Duration = 1.seconds, timeout: Duration = 1.seconds) =
            assertFailsWith<IllegalArgumentException> { work.runDue(name, interval, timeout) {} }
        refused("")
        refused("x".repeat(101))
        refused("job", interval = Duration.ZERO)
        refused("job", timeout = Duration.ZERO)
        refused("job", timeout = 6.minutes)
        assertTrue(work.runDue("x".repeat(100), 1.seconds, 5.minutes) {})
    }
}
