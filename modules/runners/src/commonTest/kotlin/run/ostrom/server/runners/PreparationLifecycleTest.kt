// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.runners.domain.Caller
import run.ostrom.server.runners.domain.CloudEnvironmentSpec
import run.ostrom.server.runners.domain.CloudRepository
import run.ostrom.server.runners.domain.CloudSession
import run.ostrom.server.runners.domain.CloudSetup
import run.ostrom.server.runners.domain.CreateCloudSessionPayload
import run.ostrom.server.runners.domain.CreateCloudSessionUseCaseImpl
import run.ostrom.server.runners.domain.DesiredState
import run.ostrom.server.runners.domain.ExecutionStatus
import run.ostrom.server.runners.domain.InvalidCloudSessionRequestException
import run.ostrom.server.runners.domain.ReconcileCloudSessionUseCaseImpl
import run.ostrom.server.runners.domain.RepositoryCloudUsageMeter
import run.ostrom.server.runners.domain.RepositorySpec
import run.ostrom.server.runners.domain.RunnerHeartbeat
import run.ostrom.server.runners.domain.RunnerHeartbeatUseCaseImpl
import run.ostrom.server.runners.domain.RunnerInstruction
import run.ostrom.server.runners.domain.SessionFailure
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.runners.fakes.FakeRunnerTokens
import run.ostrom.server.runners.fakes.FakeRuntimeDriver
import run.ostrom.server.runners.fakes.FixedPlans
import run.ostrom.server.runners.fakes.InMemoryCloudSessionsRepository
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.fakes.SettableClock
import run.ostrom.server.runners.fakes.session
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** A recipe from the session's creation to a failed preparation's end, as TeamCode's cloud sessions. */
class PreparationLifecycleTest {
    private val repository = InMemoryCloudSessionsRepository()
    private val driver = FakeRuntimeDriver()
    private val plans = FixedPlans()
    private val clock = SettableClock()
    private val meter = RepositoryCloudUsageMeter(repository)
    private val create = CreateCloudSessionUseCaseImpl(repository, plans, meter, clock)
    private val reconcile =
        ReconcileCloudSessionUseCaseImpl(repository, driver, FakeRunnerTokens(), plans, meter, clock)
    private val heartbeat = RunnerHeartbeatUseCaseImpl(repository, FakeRunnerTokens(), meter, clock)
    private val alice = Caller(ORG_A, "alice")
    private val api = CloudRepository("https://github.com/acme/api.git", "api")
    private val failure = SessionFailure("preparation_failed", "Setup command 1 (start) failed.", retryable = false)

    private fun failedStart(state: SessionState) = session(state = state)
        .copy(epoch = 1, executionStartedAt = clock.now, runnerTokenHash = "hash(tok)", failure = failure)
        .also { repository.put(it) }

    private fun stored(session: CloudSession) = repository.sessions.getValue(session.id)

    @Test
    fun aSessionIsCreatedWithItsRecipeAndNamedAfterItsFirstRepository() = runTest {
        val recipe = CloudEnvironmentSpec(repositories = listOf(api), setup = CloudSetup(start = listOf("make")))
        val created = create(alice, CreateCloudSessionPayload(null, "claude", null, environment = recipe))
        assertEquals("api", created.name)
        assertEquals(recipe, stored(created).environment)
        val setupOnly = CloudEnvironmentSpec(setup = CloudSetup(create = listOf("make")))
        val legacy = RepositorySpec("https://github.com/acme/web", null)
        val both = create(alice, CreateCloudSessionPayload(null, "shell", legacy, environment = setupOnly))
        assertEquals("web", both.name)
        val unnamed = create(alice, CreateCloudSessionPayload(null, "shell", null, environment = setupOnly))
        assertEquals("shell", unnamed.name)
        assertNull(create(alice, CreateCloudSessionPayload(null, "shell", null)).environment)
    }

    @Test
    fun aRecipeBreakingARuleOrNamingRepositoriesTwiceIsRefused() = runTest {
        val legacy = RepositorySpec("https://github.com/acme/web", null)
        val twice = CloudEnvironmentSpec(repositories = listOf(api))
        val refused = assertFailsWith<InvalidCloudSessionRequestException> {
            create(alice, CreateCloudSessionPayload("x", "claude", legacy, environment = twice))
        }
        assertTrue("not both" in refused.message.orEmpty())
        assertFailsWith<InvalidCloudSessionRequestException> {
            val outside = CloudEnvironmentSpec(workingDirectory = "/")
            create(alice, CreateCloudSessionPayload("x", "claude", null, environment = outside))
        }
        assertEquals(emptyList(), repository.sessions.values.toList())
    }

    @Test
    fun aFailedPreparationIsNeverReportedReadyAndTheRunnerIsToldToStop() = runTest {
        val failed = failedStart(SessionState.CREATING)
        val answer = heartbeat("tok", RunnerHeartbeat(failed.id, 1, ready = true, stopped = false, null))
        assertEquals(RunnerInstruction.STOP, answer.instruction)
        assertEquals(SessionState.CREATING, stored(failed).state)
        assertEquals(failure, stored(failed).failure)
    }

    @Test
    fun aFirstExecutionWhosePreparationFailedFailsAndLosesItsWorkspace() = runTest {
        val failed = failedStart(SessionState.CREATING)
        driver.workspaceDeleted = false
        assertEquals(clock.now + 2.seconds, reconcile(failed))
        assertEquals(SessionState.CREATING, stored(failed).state, "Kept until its workspace is deleted")
        driver.workspaceDeleted = true
        assertNull(reconcile(stored(failed)))
        val ended = stored(failed)
        assertEquals(SessionState.FAILED, ended.state)
        assertEquals(failure, ended.failure)
        assertNull(ended.runnerTokenHash)
        assertNull(ended.executionStartedAt)
        assertEquals(listOf("stop", "deleteWorkspace", "stop", "deleteWorkspace"), driver.calls)
    }

    @Test
    fun aResumeWhosePreparationFailedPausesAndKeepsItsWorkspace() = runTest {
        val failed = failedStart(SessionState.RESUMING)
        driver.status = ExecutionStatus.RUNNING
        assertNull(reconcile(failed))
        val paused = stored(failed)
        assertEquals(SessionState.PAUSED, paused.state)
        assertEquals(DesiredState.PAUSED, paused.desiredState, "Not resumed again by itself")
        assertEquals(failure, paused.failure)
        assertEquals(listOf("stop"), driver.calls)
        assertFalse(paused.preparationFailed, "Paused, it prepares nothing")
    }
}
