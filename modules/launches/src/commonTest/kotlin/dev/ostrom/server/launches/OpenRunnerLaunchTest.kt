// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.launches

import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.launches.domain.models.HooksCheck
import dev.ostrom.server.launches.domain.models.InvalidLaunchException
import dev.ostrom.server.launches.domain.models.LaunchRefusedException
import dev.ostrom.server.launches.domain.models.NoLaunchOrganizationException
import dev.ostrom.server.launches.domain.usecases.CreateLaunchUseCaseImpl
import dev.ostrom.server.launches.domain.usecases.LaunchPolicy
import dev.ostrom.server.launches.domain.usecases.OpenRunnerLaunchUseCaseImpl
import dev.ostrom.server.launches.fakes.ADA
import dev.ostrom.server.launches.fakes.FakeHooksCheckRules
import dev.ostrom.server.launches.fakes.FakeLaunchers
import dev.ostrom.server.launches.fakes.FakeModelRules
import dev.ostrom.server.launches.fakes.FixedClock
import dev.ostrom.server.launches.fakes.InMemoryLaunches
import dev.ostrom.server.launches.fakes.ORG
import dev.ostrom.server.launches.fakes.OTHER_ORG
import dev.ostrom.server.launches.fakes.RecordingTokens
import dev.ostrom.server.runners.domain.AgentKind
import dev.ostrom.server.runners.domain.CloudSession
import dev.ostrom.server.runners.domain.DesiredState
import dev.ostrom.server.runners.domain.RepositorySpec
import dev.ostrom.server.runners.domain.SessionLimits
import dev.ostrom.server.runners.domain.SessionState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

class OpenRunnerLaunchTest {
    private val adaMember = Uuid.parse("0000a0ad-0000-0000-0000-000000000000")
    private val launches = InMemoryLaunches()
    private val tokens = RecordingTokens()
    private val hooks = FakeHooksCheckRules(afterSeconds = 25, stopAgent = true)
    private val open = OpenRunnerLaunchUseCaseImpl(
        FakeLaunchers(mapOf(adaMember.toString() to ADA)),
        CreateLaunchUseCaseImpl(
            launches,
            LaunchPolicy(FakeModelRules(mapOf("gpt-5" to "gpt-5 is not on the allowlist."))),
            tokens,
            FixedClock(),
            hooks,
        ),
    )

    private fun cloud(
        agent: AgentKind = AgentKind.CLAUDE,
        owner: String = adaMember.toString(),
        orgId: Uuid = ORG,
        repository: RepositorySpec? = null,
        model: String? = null,
    ): CloudSession {
        val at = Instant.parse("2026-10-30T10:00:00Z")
        return CloudSession(
            id = Uuid.random(),
            orgId = orgId,
            ownerId = owner,
            name = "task",
            agent = agent,
            repository = repository,
            state = SessionState.RUNNING,
            desiredState = DesiredState.RUNNING,
            phase = null,
            epoch = 1,
            generation = 0,
            failure = null,
            limits = SessionLimits(30, 120, false),
            createdAt = at,
            updatedAt = at,
            model = model,
        )
    }

    @Test
    fun theLaunchIsTheOwnersAndRemembersItsCloudSession() = runTest {
        val session = cloud(repository = RepositorySpec("https://github.com/acme/api", "main"), model = "opus")
        val created = open(session)
        val launch = created.launch
        assertEquals(ORG to ADA.userId, launch.orgId to launch.userId)
        assertEquals("ada", launch.dev)
        assertEquals("claude-code", launch.agent, "The name the CLI reports for Claude")
        assertEquals("https://github.com/acme/api", launch.repo)
        assertEquals("opus", launch.model)
        assertEquals(session.id, launch.cloudSessionId)
        assertEquals(listOf(launch), launches.launches)
        assertEquals("osa_${launch.id}", created.agentToken)
        assertEquals(HooksCheck(25, true), created.hooksCheck, "The runner's launch is checked like the CLI's")
    }

    @Test
    fun theOtherAgentsKeepTheirNameAndASessionMayNameNoRepository() = runTest {
        for (kind in listOf(AgentKind.CODEX, AgentKind.OPENCODE, AgentKind.GEMINI)) {
            val launch = open(cloud(agent = kind)).launch
            assertEquals(kind.wire, launch.agent)
            assertNull(launch.repo)
            assertNull(launch.model)
        }
    }

    @Test
    fun aShellAGoneOwnerOrARefusedModelOpensNothing() = runTest {
        val shell = assertFailsWith<InvalidLaunchException> { open(cloud(agent = AgentKind.SHELL)) }
        assertEquals(ErrorKind.INVALID_REQUEST, shell.kind)
        assertFailsWith<NoLaunchOrganizationException> { open(cloud(owner = "not-a-member-id")) }
        assertFailsWith<NoLaunchOrganizationException> { open(cloud(owner = Uuid.random().toString())) }
        assertFailsWith<NoLaunchOrganizationException>("Another organization's member") {
            open(cloud(orgId = OTHER_ORG))
        }
        assertFailsWith<LaunchRefusedException>("The organization's policy, as for the CLI") {
            open(cloud(model = "gpt-5"))
        }
        assertEquals(emptyList(), launches.launches)
        assertEquals(emptyList(), tokens.issued)
    }
}
