// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.events

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import run.ostrom.server.events.domain.DeliverWebhooksUseCase
import run.ostrom.server.events.infrastructure.WebhooksWorker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The webhooks' worker: a delivery pass every second, the maintenance every minute, failures logged and survived. */
class WebhooksWorkerTest {
    private class Passes(var failure: Throwable? = null) : DeliverWebhooksUseCase {
        var delivered = 0
        var maintained = 0

        override suspend fun invoke(): Int {
            delivered += 1
            failure?.let { throw it }
            return 0
        }

        override suspend fun maintain(): Int {
            maintained += 1
            failure?.let { throw it }
            return 0
        }
    }

    @Test
    fun aPassRunsEverySecondAndTheMaintenanceEveryMinute() = runTest {
        val passes = Passes()
        val job = launch { WebhooksWorker(passes, RecordingLogger()).run() }
        advanceTimeBy(60.seconds + 500.milliseconds)
        job.cancel()
        assertEquals(61, passes.delivered)
        assertEquals(2, passes.maintained)
    }

    @Test
    fun aFailedPassIsLoggedByItsTypeAndTheNextOneRunsButACancellationStopsIt() = runTest {
        val logger = RecordingLogger()
        val passes = Passes(IllegalStateException("https://secret.example.com"))
        val job = launch { WebhooksWorker(passes, logger, maintainEvery = 2).run() }
        advanceTimeBy(1.seconds + 500.milliseconds)
        job.cancel()
        assertEquals(
            listOf("webhooks maintenance failed", "webhooks delivery failed", "webhooks delivery failed"),
            logger.lines.map { it.first },
        )
        assertEquals(mapOf<String, Any?>("error" to "IllegalStateException"), logger.lines.first().second)
        passes.failure = CancellationException("stop")
        assertFailsWith<CancellationException> { WebhooksWorker(passes, logger).run() }
    }
}
