// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.launches

import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.launches.LaunchSpend
import run.ostrom.server.launches.domain.models.InvalidLaunchLimitsException
import run.ostrom.server.launches.domain.models.Launch
import run.ostrom.server.launches.domain.models.LaunchEndedException
import run.ostrom.server.launches.domain.models.LaunchLimitsView
import run.ostrom.server.launches.domain.models.LaunchNotFoundException
import run.ostrom.server.launches.domain.usecases.GetLaunchLimitsUseCaseImpl
import run.ostrom.server.launches.domain.usecases.LaunchLimitRules
import run.ostrom.server.launches.domain.usecases.SetLaunchLimitsUseCaseImpl
import run.ostrom.server.launches.fakes.ADA
import run.ostrom.server.launches.fakes.FixedClock
import run.ostrom.server.launches.fakes.InMemoryLaunches
import run.ostrom.server.launches.fakes.ORG
import run.ostrom.server.launches.fakes.OTHER_ORG
import run.ostrom.server.launches.fakes.RecordingAudit
import run.ostrom.server.secrets.domain.LaunchCaps
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/** TeamCode's run policy (`PUT /runs/{id}/policy`), on a launch: who sets it, its bounds and what a read shows. */
class LaunchLimitsTest {
    private val clock = FixedClock()
    private val launches = InMemoryLaunches()
    private val audit = RecordingAudit()
    private val spent = mutableMapOf<String, Long>()
    private val spend = LaunchSpend { _, launchId -> spent[launchId] ?: 0 }
    private val admin = ADA.copy(userId = Uuid.parse("000000ca-0000-0000-0000-000000000000"), role = OrgRole.ADMIN)
    private val principal = AuthenticatedPrincipal(admin.userId.toString(), PrincipalKind.USER, setOf("sessions:write"))
    private val set = SetLaunchLimitsUseCaseImpl(launches, audit, clock, spend)
    private val read = GetLaunchLimitsUseCaseImpl(launches, spend)
    private val launch = Launch(
        "lch_1", ORG, ADA.userId, "ada", null, "claude-code", null, clock.now, clock.now + 24.hours,
    )

    private fun stored() = launches.create(launch)

    @Test
    fun anAdminSetsTheLimitsAndTheAuditLogKeepsThem() = runTest {
        stored()
        spent["lch_1"] = 1_250_000
        val caps = LaunchCaps(budgetMicros = 5_000_000, maxConcurrent = 2, paused = true)
        assertEquals(LaunchLimitsView(caps, 1_250_000), set(principal, admin, "lch_1", caps))
        assertEquals(caps, launches.find(ORG, "lch_1")?.caps)
        val record = audit.records.single()
        assertEquals("launch.limits_updated", record.event)
        assertEquals("lch_1", record.subjectId)
        assertEquals(mapOf("budgetMicros" to "5000000", "maxConcurrent" to "2", "paused" to "true"), record.details)
    }

    @Test
    fun aMemberAnotherOrganizationAndAnUnknownLaunchAreAnsweredAlike() = runTest {
        stored()
        val caps = LaunchCaps(maxConcurrent = 1)
        assertFailsWith<LaunchNotFoundException> { set(principal, ADA, "lch_1", caps) }
        assertFailsWith<LaunchNotFoundException> { set(principal, admin, "lch_x", caps) }
        assertFailsWith<LaunchNotFoundException> { set(principal, admin.copy(orgId = OTHER_ORG), "lch_1", caps) }
        assertEquals(LaunchCaps(), launches.find(ORG, "lch_1")?.caps, "Nothing changed")
        assertEquals(emptyList(), audit.records)
        assertFalse(LaunchLimitRules.administers(OrgRole.APPROVER))
        assertTrue(LaunchLimitRules.administers(OrgRole.OWNER))
    }

    @Test
    fun theBoundsAreTeamCodesAndALaunchThatEndedKeepsItsLimits() = runTest {
        stored()
        val ceiling = LaunchLimitRules.MAX_BUDGET_MICROS
        set(principal, admin, "lch_1", LaunchCaps(ceiling, LaunchLimitRules.MAX_CONCURRENT, false))
        listOf(LaunchCaps(-1), LaunchCaps(ceiling + 1), LaunchCaps(maxConcurrent = -1), LaunchCaps(maxConcurrent = 33))
            .forEach { caps ->
                val refused = assertFailsWith<InvalidLaunchLimitsException> { set(principal, admin, "lch_1", caps) }
                assertEquals("invalid_launch_limits" to ErrorKind.INVALID_REQUEST, refused.type to refused.kind)
            }
        clock.now = launch.expiresAt
        val ended = assertFailsWith<LaunchEndedException> { set(principal, admin, "lch_1", LaunchCaps(paused = true)) }
        assertEquals("launch_ended" to ErrorKind.CONFLICT, ended.type to ended.kind)
        assertEquals(1, audit.records.size, "Only the accepted change is audited")
    }

    @Test
    fun aLaunchDeletedBetweenTheReadAndTheWriteIsNotFound() = runTest {
        val vanishing = object : run.ostrom.server.launches.domain.repositories.LaunchesRepository by launches {
            override suspend fun setCaps(orgId: Uuid, id: String, caps: LaunchCaps) = false
        }
        stored()
        val use = SetLaunchLimitsUseCaseImpl(vanishing, audit, clock)
        assertFailsWith<LaunchNotFoundException> { use(principal, admin, "lch_1", LaunchCaps(paused = true)) }
    }

    @Test
    fun anAdminReadsAnyLaunchAndAMemberTheirOwnWithWhatItSpent() = runTest {
        stored()
        spent["lch_1"] = 42
        assertEquals(LaunchLimitsView(LaunchCaps(), 42), read(admin, "lch_1"))
        assertEquals(LaunchLimitsView(LaunchCaps(), 42), read(ADA, "lch_1"))
        assertFailsWith<LaunchNotFoundException> { read(ADA.copy(userId = Uuid.random()), "lch_1") }
        assertFailsWith<LaunchNotFoundException> { read(admin, "lch_x") }
        val read = GetLaunchLimitsUseCaseImpl(launches).invoke(ADA, "lch_1")
        assertEquals(0, read.spentMicros, "No ledger, nothing spent")
        val set = SetLaunchLimitsUseCaseImpl(launches, audit, clock)
        assertEquals(0, set(principal, admin, "lch_1", LaunchCaps()).spentMicros)
    }
}
