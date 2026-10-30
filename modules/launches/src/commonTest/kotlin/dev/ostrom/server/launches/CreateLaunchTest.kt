// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.launches

import dev.ostrom.server.launches.domain.models.InvalidLaunchException
import dev.ostrom.server.launches.domain.models.LaunchRefusedException
import dev.ostrom.server.launches.domain.models.NewLaunch
import dev.ostrom.server.launches.domain.usecases.CreateLaunchUseCaseImpl
import dev.ostrom.server.launches.domain.usecases.LaunchPolicy
import dev.ostrom.server.launches.fakes.ADA
import dev.ostrom.server.launches.fakes.FakeModelRules
import dev.ostrom.server.launches.fakes.FixedClock
import dev.ostrom.server.launches.fakes.InMemoryLaunches
import dev.ostrom.server.launches.fakes.ORG
import dev.ostrom.server.launches.fakes.RecordingTokens
import dev.ostrom.server.kernel.errors.ErrorKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
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
        assertEquals(24.hours, CreateLaunchUseCaseImpl.LIFETIME)
        assertEquals(listOf(launch), launches.launches)
        assertEquals(listOf(Triple(ADA, launch.id, CreateLaunchUseCaseImpl.LIFETIME)), tokens.issued)
        assertEquals("osa_${launch.id}", created.agentToken)
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
        val agent = assertFailsWith<LaunchRefusedException> { create(ADA, NewLaunch(null, "Claude Code", null)) }
        assertEquals("The agent name is not valid.", agent.message)
        assertEquals(ErrorKind.FORBIDDEN, agent.kind)
        val model = assertFailsWith<LaunchRefusedException> { create(ADA, NewLaunch(null, "codex", "gpt-5")) }
        assertEquals("gpt-5 is not on the allowlist.", model.message)
        assertTrue(launches.launches.isEmpty())
        assertTrue(tokens.issued.isEmpty())
    }
}
