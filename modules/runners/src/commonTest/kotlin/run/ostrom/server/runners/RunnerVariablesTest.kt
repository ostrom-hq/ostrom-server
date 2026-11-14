// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.runners.domain.NoRunnerVariables
import run.ostrom.server.runners.domain.RunnerVariableValues
import run.ostrom.server.runners.fakes.ALICE
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.infrastructure.ResolvedRunnerVariables
import run.ostrom.server.runners.presentation.RunnerVariablesResponse
import run.ostrom.server.secrets.domain.EnvironmentValues
import run.ostrom.server.secrets.domain.SecretValue
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
        assertEquals("RunnerVariablesResponse([TOKEN], unavailable=[])", response.toString())
    }
}
