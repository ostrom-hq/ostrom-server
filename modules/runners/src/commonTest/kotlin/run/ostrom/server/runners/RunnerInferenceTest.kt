// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.runners.domain.AgentKind
import run.ostrom.server.runners.domain.DesiredState
import run.ostrom.server.runners.domain.ExecutionFencedException
import run.ostrom.server.runners.domain.InferenceIssuance
import run.ostrom.server.runners.domain.InferenceToken
import run.ostrom.server.runners.domain.InferenceUnavailableException
import run.ostrom.server.runners.domain.IssuedInferenceCredential
import run.ostrom.server.runners.domain.NoCloudSessionWritten
import run.ostrom.server.runners.domain.RunnerCredentialsImpl
import run.ostrom.server.runners.domain.RunnerExecutionRequest
import run.ostrom.server.runners.domain.RunnerInferenceUseCase
import run.ostrom.server.runners.domain.RunnerInferenceUseCaseImpl
import run.ostrom.server.runners.domain.RunnerUnauthenticatedException
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.runners.fakes.FakeInferenceStore
import run.ostrom.server.runners.fakes.FakeRunnerTokens
import run.ostrom.server.runners.fakes.InMemoryCloudSessionsRepository
import run.ostrom.server.runners.fakes.session
import run.ostrom.server.runners.presentation.RunnerInferenceCredentialResponse
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The runner's inference credential (TeamCode's `RunnerInferenceUseCase`): only for the current, running execution. */
class RunnerInferenceTest {
    private val tokens = FakeRunnerTokens()
    private val repository = InMemoryCloudSessionsRepository()
    private val store = FakeInferenceStore()
    private val use = RunnerInferenceUseCaseImpl(RunnerCredentialsImpl(repository, tokens), store)
    private val credential = IssuedInferenceCredential("osa_secret", Instant.parse("2026-10-30T11:00:00Z"), 1.hours)

    private suspend fun running(
        state: SessionState = SessionState.RUNNING,
        desired: DesiredState = DesiredState.RUNNING,
        agent: AgentKind = AgentKind.CLAUDE,
    ): Pair<String, RunnerExecutionRequest> {
        val token = tokens.mint()
        val stored = session(state = state, desired = desired)
            .copy(agent = agent, epoch = 2, runnerTokenHash = tokens.hash(token))
        repository.put(stored)
        return token to RunnerExecutionRequest(stored.id, 2)
    }

    @Test
    fun theRunnerOfTheCurrentExecutionGetsTheStoresCredentialForAnHourKeepingTwo() = runTest {
        store.outcome = InferenceIssuance.Issued(credential)
        val holding = listOf(SessionState.CREATING, SessionState.RESUMING, SessionState.RUNNING, SessionState.PAUSING)
        for (state in holding) {
            val (token, request) = running(state)
            assertSame(credential, use(token, request), "$state")
        }
        assertEquals(1.hours to 2, RunnerInferenceUseCase.TTL to RunnerInferenceUseCase.KEEP)
        assertFalse("osa_secret" in credential.toString(), "Never in a log line")
        val token = InferenceToken(Uuid.random(), "osa_secret", credential.expiresAt)
        assertFalse("osa_secret" in token.toString())
        val response = RunnerInferenceCredentialResponse("osa_secret", "2026-10-30T11:00:00Z", 3_600_000)
        assertFalse("osa_secret" in response.toString())
        NoCloudSessionWritten.written(session())
    }

    @Test
    fun anotherExecutionOrASessionThatNoLongerRunsIsFenced() = runTest {
        store.outcome = InferenceIssuance.Issued(credential)
        assertFailsWith<RunnerUnauthenticatedException> { use("token-9", RunnerExecutionRequest(Uuid.random(), 2)) }
        val (token, request) = running()
        assertFailsWith<ExecutionFencedException> { use(token, request.copy(epoch = 1)) }
        assertFailsWith<ExecutionFencedException> { use(token, request.copy(sessionId = Uuid.random())) }
        val (deleting, deleted) = running(desired = DesiredState.TERMINATED)
        assertFailsWith<ExecutionFencedException>("Being deleted") { use(deleting, deleted) }
        val (paused, pausedRequest) = running(SessionState.PAUSED, DesiredState.PAUSED)
        assertFailsWith<ExecutionFencedException>("No execution held") { use(paused, pausedRequest) }
        store.outcome = InferenceIssuance.Fenced
        assertFailsWith<ExecutionFencedException>("Ended under the lock") { use(token, request) }
    }

    @Test
    fun aShellOrAnExecutionWithoutItsLaunchHasNoInference() = runTest {
        store.outcome = InferenceIssuance.Issued(credential)
        val (shell, shellRequest) = running(agent = AgentKind.SHELL)
        assertEquals(
            "inference_unavailable",
            assertFailsWith<InferenceUnavailableException> { use(shell, shellRequest) }.type,
        )
        store.outcome = InferenceIssuance.NoLaunch
        val (token, request) = running()
        assertFailsWith<InferenceUnavailableException> { use(token, request) }
    }
}
