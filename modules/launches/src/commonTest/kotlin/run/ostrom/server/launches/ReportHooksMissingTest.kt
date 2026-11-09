// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.launches

import run.ostrom.server.gates.domain.models.ActorKind
import run.ostrom.server.gates.domain.models.GateActor
import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.launches.domain.models.HooksMissingReport
import run.ostrom.server.launches.domain.models.InvalidLaunchException
import run.ostrom.server.launches.domain.models.Launch
import run.ostrom.server.launches.domain.models.LaunchNotFoundException
import run.ostrom.server.launches.domain.usecases.ReportHooksMissingUseCaseImpl
import run.ostrom.server.launches.fakes.ADA
import run.ostrom.server.launches.fakes.InMemoryLaunches
import run.ostrom.server.launches.fakes.ORG
import run.ostrom.server.launches.fakes.OTHER_ORG
import run.ostrom.server.launches.fakes.RecordingAudit
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ReportHooksMissingTest {
    private val at = Instant.parse("2026-11-03T10:00:00Z")
    private val launches = InMemoryLaunches().apply {
        create(Launch("lch_1", ORG, ADA.userId, "ada", "acme/api", "claude-code", null, at, at + 24.hours))
        create(Launch("lch_2", ORG, ADA.userId, "ada", null, "codex", null, at, at + 24.hours))
    }
    private val audit = RecordingAudit()
    private val report = ReportHooksMissingUseCaseImpl(launches, audit)

    private fun principal(launchId: String) = AuthenticatedPrincipal(launchId, PrincipalKind.AGENT, emptySet())

    private fun actor(launchId: String, orgId: Uuid = ORG) =
        GateActor("agent-$launchId", orgId, ActorKind.AGENT_SESSION, launchId = launchId, personId = "ada")

    @Test
    fun aLaunchsOwnAgentTokenRecordsThatNoHookRan() = runTest {
        val caller = principal("lch_1")
        report(caller, actor("lch_1"), "lch_1", HooksMissingReport(waitedSeconds = 15, stopped = true))

        assertEquals(
            listOf(
                OrgAuditRecord(
                    ORG,
                    caller,
                    "hooks.missing",
                    "launch",
                    "lch_1",
                    mapOf("agent" to "claude-code", "repo" to "acme/api", "waitedSeconds" to "15", "stopped" to "true"),
                ),
            ),
            audit.records,
        )
    }

    @Test
    fun aLaunchWithoutARepositoryLeavesItOutOfTheDetails() = runTest {
        report(principal("lch_2"), actor("lch_2"), "lch_2", HooksMissingReport(waitedSeconds = 20, stopped = false))

        assertEquals(
            mapOf("agent" to "codex", "waitedSeconds" to "20", "stopped" to "false"),
            audit.records.single().details,
        )
    }

    @Test
    fun theBoundsOfTheWaitAreAllowed() = runTest {
        report(principal("lch_1"), actor("lch_1"), "lch_1", HooksMissingReport(0, false))
        report(principal("lch_1"), actor("lch_1"), "lch_1", HooksMissingReport(3600, false))

        assertEquals(listOf("0", "3600"), audit.records.map { it.details["waitedSeconds"] })
    }

    @Test
    fun aWaitOutsideZeroToAnHourIsRefusedAndNothingIsRecorded() = runTest {
        for (seconds in listOf(-1, 3601, Int.MAX_VALUE, Int.MIN_VALUE)) {
            val refused = assertFailsWith<InvalidLaunchException>("$seconds") {
                report(principal("lch_1"), actor("lch_1"), "lch_1", HooksMissingReport(seconds, true))
            }
            assertEquals(ErrorKind.INVALID_REQUEST, refused.kind)
        }
        assertEquals(emptyList(), audit.records)
    }

    @Test
    fun anyOtherCallerFindsNoLaunchAndNothingIsRecorded() = runTest {
        val stranger = principal("lch_2")
        val callers = listOf<GateActor?>(
            actor("lch_2"), // Another launch's agent token.
            null, // A person, a device or a service key, or a revoked token: no launch.
            actor("lch_1", OTHER_ORG), // The same launch id, read in another organization.
        )
        for (caller in callers) {
            assertFailsWith<LaunchNotFoundException> {
                report(stranger, caller, "lch_1", HooksMissingReport(15, true))
            }
        }
        assertFailsWith<LaunchNotFoundException>("Unknown launch") {
            report(stranger, actor("lch_9"), "lch_9", HooksMissingReport(15, true))
        }
        assertFailsWith<LaunchNotFoundException>("A stranger learns nothing of the bounds") {
            report(stranger, null, "lch_1", HooksMissingReport(5000, true))
        }
        assertEquals(emptyList(), audit.records)
    }
}
