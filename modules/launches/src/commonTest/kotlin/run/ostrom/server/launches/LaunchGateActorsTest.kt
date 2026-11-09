// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.launches

import run.ostrom.server.gates.domain.models.ActorKind
import run.ostrom.server.gates.domain.models.GateActor
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
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
import run.ostrom.server.launches.infrastructure.LaunchAgents
import run.ostrom.server.launches.infrastructure.LaunchGateActors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

class LaunchGateActorsTest {
    private val signedIn = Instant.parse("2026-10-30T09:58:00Z")
    private val launches = InMemoryLaunches()
    private val launchers = FakeLaunchers(mapOf(ADA.userId.toString() to ADA))
    private val clock = FixedClock(signedIn)
    private val current = mutableSetOf<Uuid>()
    private val executions = CloudExecutions { _, cloud, _ -> cloud in current }
    private val actors = LaunchGateActors(launchers, LaunchAgents(launchers, launches, clock, executions))
    private val launch = Launch(
        "lch_1", ORG, ADA.userId, "ada", "acme/api", "claude-code", null, signedIn, signedIn + 24.hours,
    )

    private fun principal(subject: String, kind: PrincipalKind, org: String? = null) =
        AuthenticatedPrincipal(subject, kind, setOf("agent:session"), orgId = org, authenticatedAt = signedIn)

    @Test
    fun aPersonIsAUserOfTheirOrganizationNamedByTheirLogin() = runTest {
        val person = principal(ADA.userId.toString(), PrincipalKind.USER)
        assertEquals(
            GateActor("ada", ORG, ActorKind.USER, personId = "ada", authenticatedAt = signedIn, role = OrgRole.MEMBER),
            actors.resolve(person),
        )
        assertNull(actors.resolve(principal("000000ff-0000-0000-0000-000000000000", PrincipalKind.USER)))
    }

    @Test
    fun anAgentTokenIsTheSessionOfItsLaunchForThePersonWhoLaunchedIt() = runTest {
        launches.create(launch)
        assertEquals(
            GateActor("agent-lch_1", ORG, ActorKind.AGENT_SESSION, launchId = "lch_1", personId = "ada"),
            actors.resolve(principal("lch_1", PrincipalKind.AGENT, ORG.toString())),
        )
    }

    @Test
    fun anAgentTokenStopsWhenItsLaunchExpiresOrItsPersonMayNoLongerLaunch() = runTest {
        launches.create(launch)
        val agent = principal("lch_1", PrincipalKind.AGENT, ORG.toString())
        launchers.roles[ADA.userId] = OrgRole.BILLING
        assertNull(actors.resolve(agent), "Made billing only")
        launchers.roles[ADA.userId] = null
        assertNull(actors.resolve(agent), "Removed from the organization")
        launchers.roles.clear()
        clock.now = launch.expiresAt
        assertNull(actors.resolve(agent), "Expired")
    }

    @Test
    fun aCloudSessionsLaunchActsOnlyWhileItsExecutionIsCurrent() = runTest {
        val cloud = Uuid.random()
        launches.create(launch.copy(cloudSessionId = cloud))
        val agent = principal("lch_1", PrincipalKind.AGENT, ORG.toString())
        assertNull(actors.resolve(agent), "Its execution ended, its token still unexpired")
        current += cloud
        assertEquals("agent-lch_1", actors.resolve(agent)?.id)
    }

    @Test
    fun anAgentTokenOutsideItsLaunchOrItsOrganizationIsNobody() = runTest {
        launches.create(launch)
        assertNull(actors.resolve(principal("lch_2", PrincipalKind.AGENT, ORG.toString())), "Another launch")
        assertNull(actors.resolve(principal("lch_1", PrincipalKind.AGENT, OTHER_ORG.toString())), "Another org")
        assertNull(actors.resolve(principal("lch_1", PrincipalKind.AGENT, "not-an-org")))
        assertNull(actors.resolve(principal("lch_1", PrincipalKind.AGENT)), "No organization")
    }

    @Test
    fun aDeviceOrAServiceBelongsToNoOrganization() = runTest {
        assertNull(actors.resolve(principal(ADA.userId.toString(), PrincipalKind.DEVICE, ORG.toString())))
        assertNull(actors.resolve(principal(ADA.userId.toString(), PrincipalKind.SERVICE, ORG.toString())))
    }
}
