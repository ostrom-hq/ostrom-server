// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.launches

import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.OrgPrincipal
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.PrincipalOrgResolver
import dev.ostrom.server.launches.domain.models.Launch
import dev.ostrom.server.launches.fakes.ADA
import dev.ostrom.server.launches.fakes.FixedClock
import dev.ostrom.server.launches.fakes.InMemoryLaunches
import dev.ostrom.server.launches.fakes.ORG
import dev.ostrom.server.launches.fakes.OTHER_ORG
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
    private val member = Uuid.parse("000000ae-0000-0000-0000-000000000000")
    private val launches = InMemoryLaunches()
    private val clock = FixedClock(started)

    /** Ada is the owner of [ORG] until [roles] says otherwise; a person token reads what it is bound to. */
    private val roles = mutableMapOf<Uuid, OrgRole?>(ADA.userId to OrgRole.OWNER)
    private val asked = mutableListOf<Pair<AuthenticatedPrincipal, Uuid?>>()
    private val orgs = object : PrincipalOrgResolver {
        override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): OrgPrincipal? {
            asked += principal to orgId
            val user = Uuid.parse(principal.subjectId)
            val target = orgId ?: ORG
            return roles[user]?.takeIf { target == ORG && principal.kind == PrincipalKind.USER }?.let {
                OrgPrincipal(ORG, member, it)
            }
        }
    }
    private val callers = LaunchRelayCallers(orgs, launches, clock)
    private val launch = Launch(
        "lch_1", ORG, ADA.userId, "ada", "acme/api", "claude-code", null, started,
        started + 24.hours,
    )

    private fun agent(subject: String = "lch_1", org: String? = ORG.toString()) =
        AuthenticatedPrincipal(subject, PrincipalKind.AGENT, setOf("agent:session"), orgId = org)

    @Test
    fun aPersonIsTheMemberTheOrgsModuleResolvesWithTheirRole() = runTest {
        val person = AuthenticatedPrincipal(ADA.userId.toString(), PrincipalKind.USER, setOf("mcp:read"))
        assertEquals(Caller(ORG, member, MemberRole.OWNER), callers.resolve(person, null))
        assertNull(callers.resolve(person, OTHER_ORG))
    }

    @Test
    fun anAgentTokenIsThePersonWhoLaunchedItAsAPlainMember() = runTest {
        launches.create(launch)
        val expected = Caller(ORG, member, MemberRole.MEMBER)
        assertEquals(expected, callers.resolve(agent(), null), "An owner's agent never administers")
        assertEquals(expected, callers.resolve(agent(), ORG))
        val (person, org) = asked.last()
        assertEquals(ADA.userId.toString() to ORG.toString(), person.subjectId to person.orgId)
        assertEquals(PrincipalKind.USER to ORG, person.kind to org, "Read as the person bound to the launch's org")
    }

    @Test
    fun anAgentTokenStopsWhenItsLaunchExpiresOrItsPersonMayNoLongerLaunch() = runTest {
        launches.create(launch)
        roles[ADA.userId] = OrgRole.BILLING
        assertNull(callers.resolve(agent(), null), "Made billing only")
        roles[ADA.userId] = null
        assertNull(callers.resolve(agent(), null), "Removed from the organization")
        roles[ADA.userId] = OrgRole.MEMBER
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
