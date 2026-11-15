// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.runners.domain.RunnerCredentialsImpl
import run.ostrom.server.automations.domain.ServedSecrets
import run.ostrom.server.automations.domain.WaitSecretSource
import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.runners.domain.DesiredState
import run.ostrom.server.runners.domain.NoRunnerVariables
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.runners.fakes.session
import run.ostrom.server.runners.domain.RunnerExecutionRequest
import run.ostrom.server.runners.domain.RunnerUnauthenticatedException
import run.ostrom.server.runners.domain.RunnerVariableValues
import run.ostrom.server.runners.domain.RunnerVariablesUseCaseImpl
import run.ostrom.server.runners.fakes.FakeRunnerTokens
import run.ostrom.server.runners.fakes.InMemoryCloudSessionsRepository
import run.ostrom.server.runners.fakes.ALICE
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.infrastructure.ResolvedRunnerVariables
import run.ostrom.server.runners.presentation.RunnerVariablesResponse
import run.ostrom.server.secrets.domain.EnvironmentValues
import run.ostrom.server.secrets.domain.SecretValue
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.uuid.Uuid

/** The runner's variables on the secrets module's resolution: values revealed for the runner, never in a log line. */
class RunnerVariablesTest {
    @Test
    fun theOwnersResolvedValuesAreHandedOverAndAnUnknownOwnerHasNone() = runTest {
        val seen = mutableListOf<Pair<Uuid, Uuid>>()
        val variables = ResolvedRunnerVariables { orgId, memberId ->
            seen += orgId to memberId
            EnvironmentValues(mapOf("TOKEN" to SecretValue("s3cret")), listOf("BROKEN"))
        }
        val values = variables.of(ORG_A, ALICE.toString())
        assertEquals(mapOf("TOKEN" to "s3cret") to listOf("BROKEN"), values.variables to values.unavailable)
        assertEquals(listOf(ORG_A to ALICE), seen)
        assertFalse("s3cret" in values.toString(), "Names only in a log line")
        assertSame(RunnerVariableValues.NONE, variables.of(ORG_A, "alice"), "No member's id")
        assertSame(RunnerVariableValues.NONE, NoRunnerVariables.of(ORG_A, ALICE.toString()))
        assertEquals(1, seen.size)
        val response = RunnerVariablesResponse(mapOf("TOKEN" to "s3cret"), emptyList())
        assertEquals("RunnerVariablesResponse([TOKEN], unavailable=[], secrets=[], oneTime=[])", response.toString())
    }

    @Test
    fun aTokenNoSessionHoldsIsUnauthenticated() = runTest {
        val use = RunnerVariablesUseCaseImpl(
            RunnerCredentialsImpl(InMemoryCloudSessionsRepository(), FakeRunnerTokens()),
            NoRunnerVariables,
        )
        assertFailsWith<RunnerUnauthenticatedException> { use("token-1", RunnerExecutionRequest(Uuid.random(), 1)) }
    }

    @Test
    fun theSecretsOfAnsweredWaitsAreServedOnlyToAnActiveOwner() = runTest {
        val tokens = FakeRunnerTokens()
        val token = tokens.mint()
        val repository = InMemoryCloudSessionsRepository()
        val running = session(state = SessionState.RUNNING, ownerId = ALICE.toString())
            .copy(epoch = 1, runnerTokenHash = tokens.hash(token))
        repository.createWithinCap(running, 5)
        val values = mapOf("OSTROM_WAIT_B" to "b", "OSTROM_WAIT_A" to "a")
        val source = WaitSecretSource { _, _, _ -> ServedSecrets(values, setOf("OSTROM_WAIT_B")) }
        val request = RunnerExecutionRequest(running.id, 1)
        fun use(active: Boolean?) = RunnerVariablesUseCaseImpl(
            RunnerCredentialsImpl(repository, tokens), NoRunnerVariables, source,
            active?.let { value -> object : MemberDirectory {
                override suspend fun isActiveMember(orgId: Uuid, memberId: String) = value
            } },
        )
        val served = use(true)("token-1", request)
        assertEquals(mapOf("OSTROM_WAIT_B" to "b", "OSTROM_WAIT_A" to "a"), served.secrets)
        assertEquals(listOf("OSTROM_WAIT_B"), served.oneTime)
        assertFalse("hunter" in served.toString() || "=b" in served.toString(), "Names only in a log line")
        assertEquals(served.secrets.keys, use(null)("token-1", request).secrets.keys, "No directory: the owner stands")
        assertEquals(emptyMap(), use(false)("token-1", request).secrets, "A member who left gets none")
        val without = RunnerVariablesUseCaseImpl(RunnerCredentialsImpl(repository, tokens), NoRunnerVariables)
        assertEquals(emptyMap(), without("token-1", request).secrets)
    }
}
