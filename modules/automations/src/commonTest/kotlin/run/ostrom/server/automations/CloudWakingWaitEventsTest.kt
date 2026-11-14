// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.automations

import run.ostrom.server.automations.domain.Wait
import run.ostrom.server.automations.domain.WaitSpec
import run.ostrom.server.automations.domain.WaitStatus
import run.ostrom.server.automations.fakes.RecordingEvents
import run.ostrom.server.automations.fakes.RecordingWakes
import run.ostrom.server.automations.infrastructure.CloudWakingWaitEvents
import run.ostrom.server.automations.infrastructure.NoCloudSessionWakes
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.kernel.sessions.CloudSessionWakes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** A resolved wait wakes its session (a paused cloud session resumes), after the other listeners heard of it. */
class CloudWakingWaitEventsTest {
    private val logged = mutableListOf<Pair<String, Map<String, Any?>>>()
    private val logger = object : Logger {
        override fun log(level: LogLevel, message: String, fields: Map<String, Any?>, error: Throwable?) {
            logged += message to fields
        }

        override fun with(fields: Map<String, Any?>): Logger = this
    }
    private val org = Uuid.random()

    private fun wait(session: String?) = Wait(
        Uuid.random(), org, "human_request", WaitStatus.RESOLVED, session, null, "alice", "alice",
        WaitSpec(title = "Deploy?"), null, Instant.parse("2026-11-14T10:00:00Z"), null, null,
    )

    @Test
    fun aResolvedWaitOfASessionWakesItAfterTheOtherListeners() = runTest {
        val events = RecordingEvents()
        val order = mutableListOf<String>()
        val wakes = CloudSessionWakes { orgId, session, waitId ->
            order += "wake $session ${orgId == org} ${events.seen.lastOrNull()?.second == waitId}"
            true
        }
        val waking = CloudWakingWaitEvents(events, wakes, logger)
        val asked = wait("s1")

        waking.created(asked)
        waking.resolved(asked)
        waking.cancelled(asked)

        assertEquals(listOf("created", "resolved", "cancelled"), events.seen.map { it.first })
        assertEquals(listOf("wake s1 true true"), order, "Woken once, on the resolution only")
        assertTrue(logged.isEmpty())
    }

    @Test
    fun aWaitWithoutASessionWakesNothing() = runTest {
        val wakes = RecordingWakes()
        CloudWakingWaitEvents(RecordingEvents(), wakes, logger).resolved(wait(null))
        assertTrue(wakes.woken.isEmpty())
    }

    @Test
    fun aWakeThatFailsIsLoggedByItsTypeAndNeverFailsTheAnswer() = runTest {
        val failing = CloudSessionWakes { _, _, _ -> throw IllegalStateException("secret answer") }
        CloudWakingWaitEvents(RecordingEvents(), failing, logger).resolved(wait("s1"))
        assertEquals(
            listOf("wake of a wait's session failed" to mapOf<String, Any?>("error" to "IllegalStateException")),
            logged,
        )
    }

    @Test
    fun aCancelledWakeIsNotSwallowed() = runTest {
        val cancelled = CloudSessionWakes { _, _, _ -> throw CancellationException("stopping") }
        assertFailsWith<CancellationException> {
            CloudWakingWaitEvents(RecordingEvents(), cancelled, logger).resolved(wait("s1"))
        }
        assertTrue(logged.isEmpty())
    }

    @Test
    fun withoutTheRunnersModuleNoSessionIsWoken() = runTest {
        assertFalse(NoCloudSessionWakes.wake(org, "s1", Uuid.random()))
    }
}
