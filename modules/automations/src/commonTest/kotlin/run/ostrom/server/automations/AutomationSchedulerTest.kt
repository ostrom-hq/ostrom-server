// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.automations

import run.ostrom.server.automations.domain.AutomationCaller
import run.ostrom.server.automations.domain.AutomationRun
import run.ostrom.server.automations.domain.DryRun
import run.ostrom.server.automations.domain.RunAutomationsUseCase
import run.ostrom.server.automations.domain.RunPage
import run.ostrom.server.automations.domain.Triggered
import run.ostrom.server.automations.domain.WebhookDelivery
import run.ostrom.server.automations.infrastructure.AutomationScheduler
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class AutomationSchedulerTest {
    private val logged = mutableListOf<Pair<String, Map<String, Any?>>>()
    private val logger = object : Logger {
        override fun log(level: LogLevel, message: String, fields: Map<String, Any?>, error: Throwable?) {
            logged += message to fields
        }

        override fun with(fields: Map<String, Any?>): Logger = this
    }

    private class Ticks(var failure: Throwable? = null) : RunAutomationsUseCase {
        var count = 0

        override suspend fun tick(): Int {
            count++
            failure?.let { throw it }
            return 0
        }

        override suspend fun trigger(id: Uuid, delivery: WebhookDelivery, body: ByteArray, version: String): Triggered =
            error("unused")

        override suspend fun cancel(
            actor: AuthenticatedPrincipal,
            caller: AutomationCaller,
            id: Uuid,
            runId: Uuid,
        ): AutomationRun = error("unused")

        override suspend fun test(
            caller: AutomationCaller,
            id: Uuid,
            payload: JsonObject?,
            idempotencyKey: String?,
        ): Triggered = error("unused")

        override suspend fun dryRun(caller: AutomationCaller, id: Uuid, payload: JsonObject?): DryRun = error("unused")

        override suspend fun runs(caller: AutomationCaller, id: Uuid, before: Uuid?, limit: Int?): RunPage =
            error("unused")
    }

    @Test
    fun aFailedPassIsLoggedByItsTypeOnly() = runTest {
        val ticks = Ticks(IllegalStateException("prompt text"))
        AutomationScheduler(ticks, logger).pass()
        assertEquals(
            listOf("automations scheduler pass failed" to mapOf<String, Any?>("error" to "IllegalStateException")),
            logged,
        )
    }

    @Test
    fun aCancellationIsNeverSwallowed() = runTest {
        val ticks = Ticks(CancellationException("stop"))
        assertFailsWith<CancellationException> { AutomationScheduler(ticks, logger).pass() }
    }

    @Test
    fun runPassesEveryIntervalUntilCancelled() = runTest {
        val ticks = Ticks()
        val job = launch { AutomationScheduler(ticks, logger, 20.seconds).run() }
        advanceTimeBy(41.seconds)
        job.cancel()
        assertEquals(3, ticks.count)
    }
}
