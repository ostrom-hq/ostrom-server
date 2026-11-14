// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel

import run.ostrom.server.kernel.database.EveryCallScheduledWork
import run.ostrom.server.kernel.database.ScheduledWork
import run.ostrom.server.kernel.database.repeatDue
import run.ostrom.server.kernel.database.scheduledWork
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The loop that runs a periodic job on one replica at a time ([repeatDue]), on a scripted lease. */
class ScheduledJobsTest {
    private class Lines : Logger {
        val lines = mutableListOf<Pair<String, Map<String, Any?>>>()

        override fun log(level: LogLevel, message: String, fields: Map<String, Any?>, error: Throwable?) {
            lines += message to fields
        }

        override fun with(fields: Map<String, Any?>): Logger = this
    }

    /** A lease that answers [due] in turn (true once exhausted): a false is another replica holding the job. */
    private class Scripted(vararg due: Boolean) : ScheduledWork {
        val answers = ArrayDeque(due.toList())
        val asked = mutableListOf<String>()

        override suspend fun runDue(
            name: String,
            interval: Duration,
            timeout: Duration,
            block: suspend () -> Unit,
        ): Boolean {
            asked += name
            if (!(answers.removeFirstOrNull() ?: true)) return false
            withTimeout(timeout) { block() }
            return true
        }
    }

    @Test
    fun aJobNotDueHereIsAskedAgainEveryPollThenWaitsItsIntervalAfterARun() = runTest {
        val work = Scripted(false, false)
        var runs = 0
        val job = launch { work.repeatDue("usage/purge", 1.hours, 5.minutes, Lines(), "failed") { runs++ } }
        runCurrent()
        assertEquals(0, runs, "Another replica holds it")
        advanceTimeBy(1.minutes)
        runCurrent()
        assertEquals(0, runs, "Asked again after a minute, the poll of an hourly job")
        advanceTimeBy(1.minutes)
        runCurrent()
        assertEquals(1, runs)
        advanceTimeBy(59.minutes)
        runCurrent()
        assertEquals(1, runs, "Then nothing before its interval")
        advanceTimeBy(1.minutes + 1.milliseconds)
        assertEquals(2, runs)
        assertEquals(4, work.asked.size)
        job.cancel()
    }

    @Test
    fun aFailedOrOverrunningRunIsLoggedByItsTypeAndTheNextOneRuns() = runTest {
        val lines = Lines()
        var runs = 0
        val job = launch {
            Scripted().repeatDue("sessions/sweep", 30.seconds, 10.seconds, lines, "sweep failed") {
                runs++
                when (runs) {
                    1 -> error("secret row value")
                    2 -> delay(1.minutes)
                }
            }
        }
        advanceTimeBy(30.seconds + 10.seconds + 30.seconds + 1.milliseconds)
        assertEquals(3, runs)
        val type = { error: String ->
            "sweep failed" to mapOf<String, Any?>("job" to "sessions/sweep", "error" to error)
        }
        assertEquals(listOf(type("IllegalStateException"), type("TimeoutCancellationException")), lines.lines)
        job.cancel()
    }

    @Test
    fun aCancellationIsNeverSwallowed() = runTest {
        assertFailsWith<CancellationException> {
            Scripted().repeatDue("events/purge", 1.minutes, 1.seconds, Lines(), "failed") {
                throw CancellationException("stopping")
            }
        }
    }

    @Test
    fun theInstanceModulesLeaseIsUsedWhenBoundElseEveryCallRuns() = runTest {
        val lease = Scripted()
        val bound = koinApplication { modules(module { single<ScheduledWork> { lease } }) }
        assertSame(lease, bound.koin.scheduledWork())
        bound.close()
        val alone = koinApplication { }
        assertSame(EveryCallScheduledWork, alone.koin.scheduledWork())
        alone.close()
        var runs = 0
        assertEquals(true, EveryCallScheduledWork.runDue("a", 1.minutes, 1.seconds) { runs++ })
        assertEquals(1, runs)
    }
}
