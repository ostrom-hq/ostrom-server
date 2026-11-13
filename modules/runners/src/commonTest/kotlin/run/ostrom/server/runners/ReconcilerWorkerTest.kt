// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.runners.domain.CloudSession
import run.ostrom.server.runners.domain.ReconcileCloudSessionUseCase
import run.ostrom.server.runners.fakes.InMemoryCloudSessionsRepository
import run.ostrom.server.runners.fakes.SettableClock
import run.ostrom.server.runners.fakes.session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ReconcilerWorkerTest {
    private class RecordingLogger : Logger {
        val lines = mutableListOf<Pair<String, Map<String, Any?>>>()
        override fun log(level: LogLevel, message: String, fields: Map<String, Any?>, error: Throwable?) {
            lines += message to fields
        }
        override fun with(fields: Map<String, Any?>): Logger = this
    }

    private val repository = InMemoryCloudSessionsRepository()
    private val clock = SettableClock()
    private val logger = RecordingLogger()

    private fun worker(
        step: suspend (CloudSession) -> Instant?,
    ) = run.ostrom.server.runners.infrastructure.ReconcilerWorker(
        repository,
        object : ReconcileCloudSessionUseCase {
            override suspend fun invoke(session: CloudSession): Instant? = step(session)
        },
        logger,
        clock,
        owner = "test",
    )

    @Test
    fun aPassReconcilesDueSessionsAndReleasesThemWithTheirNextTime() = runTest {
        val due = session().also { repository.put(it); repository.next[it.id] = clock.now }
        val later = session().also { repository.put(it); repository.next[it.id] = clock.now + 10.seconds }

        assertEquals(1, worker { clock.now + 5.seconds }.pass())

        assertEquals(listOf<Pair<Uuid, Instant?>>(due.id to clock.now + 5.seconds), repository.released)
        assertEquals(clock.now + 10.seconds, repository.next[later.id])
    }

    @Test
    fun aFailedStepIsLoggedWithoutItsMessageAndRetriedLater() = runTest {
        val due = session().also { repository.put(it); repository.next[it.id] = clock.now }

        worker { throw IllegalStateException("cluster said: secret body") }.pass()

        assertEquals(listOf<Pair<Uuid, Instant?>>(due.id to clock.now + 30.seconds), repository.released)
        val (message, fields) = logger.lines.single()
        assertEquals("cloud session step failed", message)
        assertEquals("IllegalStateException", fields["error"])
    }

    @Test
    fun aCancellationIsNeverSwallowed() = runTest {
        session().also { repository.put(it); repository.next[it.id] = clock.now }
        assertFailsWith<CancellationException> { worker { throw CancellationException("stop") }.pass() }
    }

    @Test
    fun runLoopsUntilCancelled() = runTest {
        var steps = 0
        val worker = worker { steps++; null }
        val job = launch { worker.run() }
        session().also { repository.put(it); repository.next[it.id] = clock.now }
        advanceTimeBy(2_500)
        job.cancel()
        assertEquals(1, steps)
    }
}
