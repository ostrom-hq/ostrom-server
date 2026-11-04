// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.launches

import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.OrgPrincipal
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.PrincipalOrgResolver
import dev.ostrom.server.launches.domain.models.Launch
import dev.ostrom.server.launches.domain.services.CloudExecutions
import dev.ostrom.server.launches.fakes.ADA
import dev.ostrom.server.launches.fakes.FakeLaunchers
import dev.ostrom.server.launches.fakes.FixedClock
import dev.ostrom.server.launches.fakes.InMemoryLaunches
import dev.ostrom.server.launches.fakes.ORG
import dev.ostrom.server.launches.fakes.OTHER_ORG
import dev.ostrom.server.launches.fakes.memberOf
import dev.ostrom.server.launches.infrastructure.LaunchAgents
import dev.ostrom.server.launches.infrastructure.LaunchRelayCallers
import dev.ostrom.server.secrets.domain.Caller
import dev.ostrom.server.secrets.domain.MemberRole
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

class LaunchRelayCallersTest {
    private val started = Instant.parse("2026-10-30T09:58:00Z")
    private val launches = InMemoryLaunches()
    private val clock = FixedClock(started)
    private val executions = CloudExecutions { _, _, _ -> true }
    private val launchers = FakeLaunchers(mapOf(ADA.userId.toString() to ADA.copy(role = OrgRole.OWNER)))

    /** The orgs module for a person's own credential: Ada owns [ORG]. */
    private val orgs = object : PrincipalOrgResolver {
        override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): OrgPrincipal? =
            OrgPrincipal(ORG, memberOf(ADA.userId), OrgRole.OWNER).takeIf {
                principal.subjectId == ADA.userId.toString() && (orgId == null || orgId == ORG)
            }
    }
    private val callers = LaunchRelayCallers(orgs, LaunchAgents(launchers, launches, clock, executions))
    private val launch =
        Launch("lch_1", ORG, ADA.userId, "ada", "acme/api", "claude-code", null, started, started + 24.hours)

    private fun agent(subject: String = "lch_1", org: String? = ORG.toString()) =
        AuthenticatedPrincipal(subject, PrincipalKind.AGENT, setOf("agent:session"), orgId = org)

    @Test
    fun aPersonIsTheMemberTheOrgsModuleResolvesWithTheirRole() = runTest {
        val person = AuthenticatedPrincipal(ADA.userId.toString(), PrincipalKind.USER, setOf("mcp:read"))
        assertEquals(Caller(ORG, memberOf(ADA.userId), MemberRole.OWNER), callers.resolve(person, null))
        assertNull(callers.resolve(person, OTHER_ORG))
    }

    @Test
    fun anAgentTokenIsThePersonWhoLaunchedItAsAPlainMember() = runTest {
        launches.create(launch)
        val expected = Caller(ORG, memberOf(ADA.userId), MemberRole.MEMBER, launchId = launch.id)
        assertEquals(
            expected,
            callers.resolve(agent(), null),
            "An owner's agent never administers, and names its launch",
        )
        assertEquals(expected, callers.resolve(agent(), ORG))
    }

    @Test
    fun anAgentTokenOfALaunchThatPlaysAWorkflowNamesIt() = runTest {
        launches.create(launch.copy(workflowId = "review"))
        assertEquals(
            Caller(ORG, memberOf(ADA.userId), MemberRole.MEMBER, launchId = launch.id, workflowId = "review"),
            callers.resolve(agent(), null),
            "Every model call of the launch counts under its workflow",
        )
    }

    @Test
    fun anAgentTokenStopsWhenItsLaunchExpiresOrItsPersonMayNoLongerLaunch() = runTest {
        launches.create(launch)
        launchers.roles[ADA.userId] = OrgRole.BILLING
        assertNull(callers.resolve(agent(), null), "Made billing only")
        launchers.roles[ADA.userId] = null
        assertNull(callers.resolve(agent(), null), "Removed from the organization")
        launchers.roles.clear()
        clock.now = launch.expiresAt
        assertNull(callers.resolve(agent(), null), "Expired")
    }

    @Test
    fun anAgentTokenOutsideItsLaunchOrItsOrganizationIsNobody() = runTest {
        launches.create(launch)
        assertNull(callers.resolve(agent("lch_2"), null), "Another launch")
        assertNull(callers.resolve(agent(), OTHER_ORG), "Asked for another organization")
        assertNull(callers.resolve(agent(org = OTHER_ORG.toString()), null), "Bound to another organization")
        assertNull(callers.resolve(agent(org = "not-an-org"), null))
        assertNull(callers.resolve(agent(org = null), null), "No organization")
    }
}
