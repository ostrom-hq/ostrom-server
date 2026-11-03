// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.launches

import dev.ostrom.server.automations.domain.WaitAgent
import dev.ostrom.server.gates.domain.services.GateSessions
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import dev.ostrom.server.launches.domain.models.Launch
import dev.ostrom.server.launches.domain.services.CloudExecutions
import dev.ostrom.server.launches.fakes.ADA
import dev.ostrom.server.launches.fakes.FakeLaunchers
import dev.ostrom.server.launches.fakes.FixedClock
import dev.ostrom.server.launches.fakes.InMemoryLaunches
import dev.ostrom.server.launches.fakes.ORG
import dev.ostrom.server.launches.fakes.memberOf
import dev.ostrom.server.launches.infrastructure.LaunchAgents
import dev.ostrom.server.launches.infrastructure.LaunchWaitAgents
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

class LaunchWaitAgentsTest {
    private val started = Instant.parse("2026-10-30T09:58:00Z")
    private val launches = InMemoryLaunches()
    private val clock = FixedClock(started)
    private val executions = CloudExecutions { _, _, _ -> true }
    private val launchers = FakeLaunchers(mapOf(ADA.userId.toString() to ADA.copy(role = OrgRole.OWNER)))
    private val launch =
        Launch("lch_1", ORG, ADA.userId, "ada", "acme/api", "claude-code", null, started, started + 24.hours)

    /** The sessions module's guard: `s-1` is an open session of `lch_1`; it records each check. */
    private class RecordingSessions : GateSessions {
        val checked = mutableListOf<Triple<Uuid, String, String?>>()

        override suspend fun assertOpen(orgId: Uuid, sessionId: String, launchId: String?) {
            checked += Triple(orgId, sessionId, launchId)
            if (sessionId != "s-1" || launchId != "lch_1") {
                throw OstromException(ErrorKind.FORBIDDEN, "This session is not yours.")
            }
        }
    }

    private val sessions = RecordingSessions()
    private val agents = LaunchWaitAgents(LaunchAgents(launchers, launches, clock, executions), sessions)

    private fun agent(subject: String = "lch_1") =
        AuthenticatedPrincipal(subject, PrincipalKind.AGENT, setOf("agent:session"), orgId = ORG.toString())

    @Test
    fun anAgentTokenIsThePersonWhoLaunchedItInsideItsLaunchsSession() = runTest {
        launches.create(launch)
        val agent = WaitAgent(ORG, memberOf(ADA.userId).toString(), "s-1", listOf("s-1"))
        assertEquals(agent, agents.resolve(agent(), "s-1"))
        val expected: List<Triple<Uuid, String, String?>> = listOf(Triple(ORG, "s-1", "lch_1"))
        assertEquals(expected, sessions.checked, "Checked as the gates check it")
    }

    @Test
    fun anotherLaunchsSessionIsRefusedByTheSessionsGuard() = runTest {
        launches.create(launch)
        val refused = assertFailsWith<OstromException> { agents.resolve(agent(), "s-2") }
        assertEquals(ErrorKind.FORBIDDEN, refused.kind)
    }

    @Test
    fun aTokenWhoseLaunchIsOverOrNoAgentTokenIsNobodyAndNoSessionIsRead() = runTest {
        launches.create(launch)
        clock.now = launch.expiresAt
        assertNull(agents.resolve(agent(), "s-1"), "Expired")
        assertNull(agents.resolve(agent("lch_2"), "s-1"), "Another launch")
        clock.now = started
        val person = AuthenticatedPrincipal("lch_1", PrincipalKind.USER, setOf("user:*"), orgId = ORG.toString())
        assertNull(agents.resolve(person, "s-1"), "A person's credential named like a launch is no agent")
        assertTrue(sessions.checked.isEmpty(), "No session is read for a token that is nobody")
    }
}
