// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners

import dev.ostrom.server.automations.domain.AutomationAction
import dev.ostrom.server.automations.domain.LaunchRequest
import dev.ostrom.server.automations.domain.LaunchResult
import dev.ostrom.server.runners.domain.CloudPlan
import dev.ostrom.server.runners.domain.CloudPlanProvider
import dev.ostrom.server.runners.domain.CreateCloudSessionUseCaseImpl
import dev.ostrom.server.runners.domain.RepositoryCloudUsageMeter
import dev.ostrom.server.runners.domain.SessionState
import dev.ostrom.server.runners.fakes.FixedPlans
import dev.ostrom.server.runners.fakes.InMemoryCloudSessionsRepository
import dev.ostrom.server.runners.fakes.ORG_A
import dev.ostrom.server.runners.fakes.ORG_B
import dev.ostrom.server.runners.fakes.SettableClock
import dev.ostrom.server.runners.fakes.session
import dev.ostrom.server.runners.infrastructure.CloudSessionAutomationLauncher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class CloudSessionAutomationLauncherTest {
    private val repository = InMemoryCloudSessionsRepository()
    private val plans = FixedPlans()
    private val meter = RepositoryCloudUsageMeter(repository)
    private val launcher = CloudSessionAutomationLauncher(
        CreateCloudSessionUseCaseImpl(repository, plans, meter, SettableClock()),
        repository,
    )

    private fun request(agent: String = "claude", model: String? = "claude-sonnet-4-5") = LaunchRequest(
        orgId = ORG_A,
        owner = "alice",
        automationId = Uuid.random(),
        runId = Uuid.random(),
        name = "Nightly",
        action = AutomationAction("unused: the rendered prompt is sent", agent, model),
        prompt = "Summarize 2026-10-30",
    )

    @Test
    fun aRunStartsASessionOwnedByTheAutomationsOwnerWithItsPrompt() = runTest {
        val started = assertIs<LaunchResult.Started>(launcher.launch(request()))
        val session = repository.sessions.getValue(Uuid.parse(started.sessionId))
        assertEquals(ORG_A, session.orgId)
        assertEquals("alice", session.ownerId)
        assertEquals("Nightly", session.name)
        assertEquals("Summarize 2026-10-30", session.prompt)
        assertEquals("claude-sonnet-4-5", session.model)
        assertEquals(SessionState.CREATING, session.state)
    }

    @Test
    fun thePlansRefusalsBecomeStableReasons() = runTest {
        plans.plan = CloudPlan.FREE.copy(available = false)
        assertEquals(LaunchResult.Refused("cloud_sessions_unavailable"), launcher.launch(request()))
        plans.plan = CloudPlan.FREE.copy(monthlyHours = 0)
        assertEquals(LaunchResult.Refused("cloud_hours_exhausted"), launcher.launch(request()))
        plans.plan = CloudPlan.FREE.copy(maxRunningSessions = 0)
        assertEquals(LaunchResult.Refused("cloud_session_limit"), launcher.launch(request()))
        plans.plan = CloudPlan.FREE
        assertEquals(LaunchResult.Refused("invalid_session_request"), launcher.launch(request(agent = "nope")))
    }

    @Test
    fun anUnexpectedFailurePropagates() = runTest {
        val failing = CloudSessionAutomationLauncher(
            CreateCloudSessionUseCaseImpl(repository, DownPlans, meter, SettableClock()),
            repository,
        )
        assertFailsWith<IllegalStateException> { failing.launch(request()) }
    }

    @Test
    fun aSessionWorksWhileItHoldsAnExecutionInItsOrganization() = runTest {
        val running = session(SessionState.RUNNING).also(repository::put)
        val paused = session(SessionState.PAUSED).also(repository::put)
        assertTrue(launcher.isWorking(ORG_A, running.id.toString()))
        assertFalse(launcher.isWorking(ORG_B, running.id.toString()))
        assertFalse(launcher.isWorking(ORG_A, paused.id.toString()))
        assertFalse(launcher.isWorking(ORG_A, Uuid.random().toString()))
        assertFalse(launcher.isWorking(ORG_A, "not-a-session"))
    }
}

private object DownPlans : CloudPlanProvider {
    override suspend fun planFor(orgId: Uuid): CloudPlan = error("plan store down")
}
