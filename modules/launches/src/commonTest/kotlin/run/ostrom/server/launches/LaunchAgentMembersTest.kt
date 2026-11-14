// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.launches

import run.ostrom.server.kernel.auth.AgentMember
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.launches.domain.models.Launch
import run.ostrom.server.launches.domain.services.CloudExecutions
import run.ostrom.server.launches.fakes.ADA
import run.ostrom.server.launches.fakes.FakeLaunchers
import run.ostrom.server.launches.fakes.FixedClock
import run.ostrom.server.launches.fakes.InMemoryLaunches
import run.ostrom.server.launches.fakes.ORG
import run.ostrom.server.launches.fakes.OTHER_ORG
import run.ostrom.server.launches.fakes.memberOf
import run.ostrom.server.launches.infrastructure.LaunchAgentMembers
import run.ostrom.server.launches.infrastructure.LaunchAgents
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The kernel's reading of an agent token (the artifacts module's): the person who launched it, with their role. */
class LaunchAgentMembersTest {
    private val started = Instant.parse("2026-10-30T09:58:00Z")
    private val launches = InMemoryLaunches()
    private val clock = FixedClock(started)
    private var running = true
    private val executions = CloudExecutions { _, _, _ -> running }
    private val launchers = FakeLaunchers(mapOf(ADA.userId.toString() to ADA.copy(role = OrgRole.OWNER)))
    private val members = LaunchAgentMembers(LaunchAgents(launchers, launches, clock, executions))
    private val launch =
        Launch("lch_1", ORG, ADA.userId, "ada", "acme/api", "claude-code", null, started, started + 24.hours)

    private fun agent(kind: PrincipalKind = PrincipalKind.AGENT) =
        AuthenticatedPrincipal("lch_1", kind, setOf("agent:session"), orgId = ORG.toString())

    @Test
    fun anAgentTokenIsThePersonWhoLaunchedItWithTheirRoleAndItsLaunch() = runTest {
        launches.create(launch)
        val ada = OrgPrincipal(ORG, memberOf(ADA.userId), OrgRole.OWNER)
        assertEquals(AgentMember(ada, "lch_1"), members.resolve(agent(), null))
        assertEquals(AgentMember(ada, "lch_1"), members.resolve(agent(), ORG))
        assertNull(members.resolve(agent(), OTHER_ORG), "Asked for another organization")
    }

    @Test
    fun aCloudSessionsAgentNamesItsSessionUntilItsExecutionEnds() = runTest {
        val session = Uuid.random()
        launches.create(launch.copy(cloudSessionId = session))
        assertEquals(session.toString(), members.resolve(agent(), null)?.cloudSessionId)
        running = false
        assertNull(members.resolve(agent(), null), "The execution ended")
    }

    @Test
    fun onlyAnAgentTokenIsRead() = runTest {
        launches.create(launch)
        assertNull(members.resolve(agent(PrincipalKind.USER), null))
        assertNull(members.resolve(agent(PrincipalKind.SERVICE), null))
    }
}
