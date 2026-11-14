// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.automations.domain.AutomationAction
import run.ostrom.server.automations.domain.LaunchRequest
import run.ostrom.server.automations.domain.LaunchResult
import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.runners.domain.CloudPlan
import run.ostrom.server.runners.domain.Caller
import run.ostrom.server.runners.domain.CloudSession
import run.ostrom.server.runners.domain.ClusterUnavailableException
import run.ostrom.server.runners.domain.GitHubOwnerOnlyException
import run.ostrom.server.runners.domain.ImageNotAllowedException
import run.ostrom.server.runners.domain.NamespaceNotAllowedException
import run.ostrom.server.runners.domain.ProfileRefusals
import run.ostrom.server.runners.domain.ProfileRequiredException
import run.ostrom.server.runners.domain.ProfileUnassignedException
import run.ostrom.server.runners.domain.SessionFailure
import run.ostrom.server.runners.domain.SessionProfile
import run.ostrom.server.runners.domain.SessionProfileNotFoundException
import run.ostrom.server.runners.domain.SessionProfiles
import run.ostrom.server.runners.domain.ChangeCloudSessionUseCaseImpl
import run.ostrom.server.runners.domain.CloudPlanProvider
import run.ostrom.server.runners.domain.DesiredState
import run.ostrom.server.runners.domain.CreateCloudSessionUseCaseImpl
import run.ostrom.server.runners.domain.RepositoryCloudUsageMeter
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.runners.fakes.FixedPlans
import run.ostrom.server.runners.fakes.InMemoryCloudSessionsRepository
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.fakes.ORG_B
import run.ostrom.server.runners.fakes.SettableClock
import run.ostrom.server.runners.fakes.session
import run.ostrom.server.runners.infrastructure.CloudSessionAutomationLauncher
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
    private val clock = SettableClock()
    private val launcher = CloudSessionAutomationLauncher(
        CreateCloudSessionUseCaseImpl(repository, plans, meter, clock),
        repository,
        ChangeCloudSessionUseCaseImpl(repository, plans, meter, clock),
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
    fun aRunLaunchesOnItsActionsProfileAndTheProfilesRefusalsBecomeTheirKeys() = runTest {
        val profiles = AskedProfiles()
        val onProfiles = CloudSessionAutomationLauncher(
            CreateCloudSessionUseCaseImpl(repository, plans, meter, clock, profiles),
            repository,
            ChangeCloudSessionUseCaseImpl(repository, plans, meter, clock),
        )
        val profile = Uuid.random()
        val named = request().let { it.copy(action = it.action.copy(profileId = profile.toString())) }
        assertIs<LaunchResult.Started>(onProfiles.launch(named))
        assertIs<LaunchResult.Started>(onProfiles.launch(request()))
        assertEquals(listOf(profile, null), profiles.asked)
        val refusals = listOf(
            SessionProfileNotFoundException(),
            ProfileUnassignedException(),
            ProfileRequiredException(),
            ImageNotAllowedException(),
            ClusterUnavailableException(),
            NamespaceNotAllowedException(),
        )
        for (refusal in refusals) {
            profiles.refusal = refusal
            assertEquals(LaunchResult.Refused(refusal.type), onProfiles.launch(named))
        }
        assertEquals(ProfileRefusals.KEYS, refusals.map { it.type }.toSet())
        profiles.refusal = GitHubOwnerOnlyException()
        assertFailsWith<GitHubOwnerOnlyException> { onProfiles.launch(named) }
    }

    @Test
    fun anUnexpectedFailurePropagates() = runTest {
        val failing = CloudSessionAutomationLauncher(
            CreateCloudSessionUseCaseImpl(repository, DownPlans, meter, SettableClock()),
            repository,
            ChangeCloudSessionUseCaseImpl(repository, plans, meter, clock),
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

    @Test
    fun aCancelledRunsSessionIsPausedAsItsOwnerAndOneThatIsGoneOrOverIsNotPaused() = runTest {
        val running = session(SessionState.RUNNING).also(repository::put)
        assertTrue(launcher.pause(ORG_A, "alice", running.id.toString()))
        assertEquals(DesiredState.PAUSED, repository.sessions.getValue(running.id).desiredState)
        // Pausing a paused session changes nothing.
        assertTrue(launcher.pause(ORG_A, "alice", running.id.toString()))
        assertFalse(launcher.pause(ORG_A, "bob", running.id.toString()))
        assertFalse(launcher.pause(ORG_B, "alice", running.id.toString()))
        assertFalse(launcher.pause(ORG_A, "alice", Uuid.random().toString()))
        assertFalse(launcher.pause(ORG_A, "alice", "not-a-session"))
        val over = session(SessionState.TERMINATED).also(repository::put)
        assertFalse(launcher.pause(ORG_A, "alice", over.id.toString()))
    }
}

private object DownPlans : CloudPlanProvider {
    override suspend fun planFor(orgId: Uuid): CloudPlan = error("plan store down")
}

/** Records the profile each creation asks for, and refuses with [refusal] when it is set. */
private class AskedProfiles : SessionProfiles {
    val asked = mutableListOf<Uuid?>()
    var refusal: OstromException? = null

    override suspend fun choose(caller: Caller, profileId: Uuid?, image: String?): SessionProfile {
        asked += profileId
        refusal?.let { throw it }
        return SessionProfile(clusterId = "c", namespace = "ns", image = "img", workspaceGibibytes = 1)
    }

    override suspend fun startFailure(session: CloudSession): SessionFailure? = null

    override suspend fun legacy(): SessionProfile? = null
}
