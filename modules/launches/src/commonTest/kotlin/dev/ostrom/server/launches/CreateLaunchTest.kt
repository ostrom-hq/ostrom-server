// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.launches

import dev.ostrom.server.identity.domain.MAX_AGENT_TOKEN_LIFETIME
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.launches.domain.models.InvalidLaunchException
import dev.ostrom.server.launches.domain.models.LaunchLimit
import dev.ostrom.server.launches.domain.models.LaunchRateLimitedException
import dev.ostrom.server.launches.domain.models.LaunchRefusedException
import dev.ostrom.server.launches.domain.models.LaunchWorkflowNotFoundException
import dev.ostrom.server.launches.domain.models.NewLaunch
import dev.ostrom.server.launches.domain.usecases.CreateLaunchUseCaseImpl
import dev.ostrom.server.launches.domain.usecases.LaunchPolicy
import dev.ostrom.server.launches.fakes.ADA
import dev.ostrom.server.launches.fakes.FakeModelRules
import dev.ostrom.server.launches.fakes.FakeWorkflows
import dev.ostrom.server.launches.fakes.FixedClock
import dev.ostrom.server.launches.fakes.InMemoryLaunches
import dev.ostrom.server.launches.fakes.ORG
import dev.ostrom.server.launches.fakes.OTHER_ORG
import dev.ostrom.server.launches.fakes.RecordingTokens
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class CreateLaunchTest {
    private val clock = FixedClock()
    private val launches = InMemoryLaunches()
    private val tokens = RecordingTokens()
    private val create = CreateLaunchUseCaseImpl(
        launches,
        LaunchPolicy(FakeModelRules(mapOf("gpt-5" to "gpt-5 is not on the allowlist."))),
        tokens,
        clock,
    )

    @Test
    fun aLaunchIsStoredForItsPersonThenItsAgentTokenIsIssued() = runTest {
        val created = create(ADA, NewLaunch("acme/api", "claude-code", "claude-opus-4-1"))
        val launch = created.launch
        assertTrue(Regex("lch_[0-9a-f]{32}").matches(launch.id), launch.id)
        assertEquals(ORG, launch.orgId)
        assertEquals(ADA.userId, launch.userId)
        assertEquals("ada", launch.dev)
        assertEquals("acme/api", launch.repo)
        assertEquals("claude-code", launch.agent)
        assertEquals("claude-opus-4-1", launch.model)
        assertEquals(Instant.parse("2026-10-30T10:00:00.123Z"), launch.createdAt, "Kept to the millisecond")
        assertEquals(launch.createdAt + 24.hours, launch.expiresAt)
        assertEquals(MAX_AGENT_TOKEN_LIFETIME, CreateLaunchUseCaseImpl.LIFETIME, "Never above what identity issues")
        assertEquals(listOf(launch), launches.launches)
        assertEquals(listOf(Triple(ADA, launch.id, CreateLaunchUseCaseImpl.LIFETIME)), tokens.issued)
        assertEquals("osa_${launch.id}", created.agentToken)
    }

    @Test
    fun aLaunchOverTheLimitIsRefusedWithItsWaitAndIssuesNoToken() = runTest {
        create(ADA, NewLaunch(null, "codex", null))
        assertEquals(listOf(LaunchLimit.DEFAULT), launches.limits, "The server's limits by default")
        launches.refusal = 90.seconds
        val refused = assertFailsWith<LaunchRateLimitedException> { create(ADA, NewLaunch(null, "codex", null)) }
        assertEquals(ErrorKind.RATE_LIMITED, refused.kind)
        assertEquals(90.seconds, refused.retryAfter)
        assertEquals("Too many launches: try again in 91 s.", refused.message)
        assertEquals(1, launches.launches.size)
        assertEquals(1, tokens.issued.size, "Only the launch that was stored has a token")
    }

    @Test
    fun aLaunchMayNameNeitherItsRepositoryNorItsModel() = runTest {
        val launch = create(ADA, NewLaunch(null, "codex", null)).launch
        assertNull(launch.repo)
        assertNull(launch.model)
        val other = create(ADA, NewLaunch(null, "codex", null)).launch
        assertTrue(launch.id != other.id, "Each launch has its own id")
    }

    @Test
    fun aMalformedRepositoryIsRefusedBeforeAnythingIsStored() = runTest {
        val repos = listOf("", "r".repeat(CreateLaunchUseCaseImpl.MAX_REPO + 1), "acme/api\nX-Injected: 1")
        for (repo in repos) {
            val error = assertFailsWith<InvalidLaunchException> { create(ADA, NewLaunch(repo, "codex", null)) }
            assertEquals(ErrorKind.INVALID_REQUEST, error.kind)
        }
        assertEquals("r".repeat(200), create(ADA, NewLaunch("r".repeat(200), "codex", null)).launch.repo)
        assertEquals(1, launches.launches.size)
    }

    @Test
    fun aLaunchThePolicyRefusesIsNeitherStoredNorGivenAToken() = runTest {
        val agent = assertFailsWith<InvalidLaunchException> { create(ADA, NewLaunch(null, "Claude Code", null)) }
        assertEquals("The agent name is not valid." to ErrorKind.INVALID_REQUEST, agent.message to agent.kind)
        val badModel = assertFailsWith<InvalidLaunchException> { create(ADA, NewLaunch(null, "codex", "a b")) }
        assertEquals("The model name is not valid.", badModel.message)
        val billing = ADA.copy(role = OrgRole.BILLING)
        val payer = assertFailsWith<LaunchRefusedException> { create(billing, NewLaunch(null, "codex", null)) }
        assertEquals(LaunchPolicy.BILLING_ONLY to ErrorKind.FORBIDDEN, payer.message to payer.kind)
        val model = assertFailsWith<LaunchRefusedException> { create(ADA, NewLaunch(null, "codex", "gpt-5")) }
        assertEquals("gpt-5 is not on the allowlist.", model.message)
        assertTrue(launches.launches.isEmpty())
        assertTrue(tokens.issued.isEmpty())
    }

    private fun playing(workflows: FakeWorkflows) =
        CreateLaunchUseCaseImpl(launches, LaunchPolicy(FakeModelRules()), tokens, clock, workflows = workflows)

    @Test
    fun aLaunchNamesTheWorkflowItPlaysAndKeepsIt() = runTest {
        val workflows = FakeWorkflows(mapOf((ORG to "review") to "Review"))
        val create = playing(workflows)
        val launch = create(ADA, NewLaunch("acme/api", "claude-code", null, workflowId = "review")).launch
        assertEquals("review", launch.workflowId)
        assertEquals(listOf("review"), launches.launches.map { it.workflowId })
        assertEquals(listOf(ORG to "review"), workflows.asked, "Read in the launcher's organization")
        assertNull(create(ADA, NewLaunch(null, "codex", null)).launch.workflowId, "Without one, none")
    }

    @Test
    fun aWorkflowOfNoneOrOfAnotherOrganizationIsNotFoundAndNothingIsLaunched() = runTest {
        val workflows = FakeWorkflows(mapOf((OTHER_ORG to "review") to "Review"))
        val create = playing(workflows)
        val other = assertFailsWith<LaunchWorkflowNotFoundException> {
            create(ADA, NewLaunch(null, "codex", null, workflowId = "review"))
        }
        assertEquals(ErrorKind.NOT_FOUND, other.kind)
        assertEquals("Workflow not found.", other.message)
        assertFailsWith<LaunchWorkflowNotFoundException> {
            this@CreateLaunchTest.create(ADA, NewLaunch(null, "codex", null, workflowId = "review"))
        }
        assertTrue(launches.launches.isEmpty())
        assertTrue(tokens.issued.isEmpty())
    }

    @Test
    fun aMalformedWorkflowIdIsRefusedBeforeAnyLookup() = runTest {
        val workflows = FakeWorkflows(emptyMap())
        val create = playing(workflows)
        for (id in listOf("", "w".repeat(CreateLaunchUseCaseImpl.MAX_WORKFLOW_ID + 1), "re\nview")) {
            val refused = assertFailsWith<InvalidLaunchException> {
                create(ADA, NewLaunch(null, "codex", null, workflowId = id))
            }
            assertEquals("workflowId: 1 to 200 characters, without control characters.", refused.message)
        }
        assertEquals(emptyList(), workflows.asked)
        assertTrue(launches.launches.isEmpty())
    }
}
