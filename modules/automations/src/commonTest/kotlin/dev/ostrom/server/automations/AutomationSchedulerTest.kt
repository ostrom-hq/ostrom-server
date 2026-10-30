// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.automations

import dev.ostrom.server.automations.domain.AutomationCaller
import dev.ostrom.server.automations.domain.DryRun
import dev.ostrom.server.automations.domain.RunAutomationsUseCase
import dev.ostrom.server.automations.domain.RunPage
import dev.ostrom.server.automations.domain.Triggered
import dev.ostrom.server.automations.domain.WebhookDelivery
import dev.ostrom.server.automations.infrastructure.AutomationScheduler
import dev.ostrom.server.kernel.logging.LogLevel
import dev.ostrom.server.kernel.logging.Logger
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

        override suspend fun trigger(id: Uuid, delivery: WebhookDelivery, body: ByteArray): Triggered = error("unused")

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
