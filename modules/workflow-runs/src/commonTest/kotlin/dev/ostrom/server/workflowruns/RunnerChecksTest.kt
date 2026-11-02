// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.workflowruns

import dev.ostrom.core.api.models.CheckAttestation
import dev.ostrom.core.api.models.CheckOutcome
import dev.ostrom.core.api.models.ReportCheckRequest
import dev.ostrom.server.workflowruns.domain.models.InvalidRunException
import dev.ostrom.server.workflowruns.domain.models.RunSessionNotFoundException
import dev.ostrom.server.workflowruns.domain.usecases.ReportCheckUseCaseImpl
import dev.ostrom.server.workflowruns.domain.usecases.ReportRunnerCheckUseCaseImpl
import dev.ostrom.server.workflowruns.fakes.FakeLaunches
import dev.ostrom.server.workflowruns.fakes.FakeSessions
import dev.ostrom.server.workflowruns.fakes.FixedClock
import dev.ostrom.server.workflowruns.fakes.InMemoryRuns
import dev.ostrom.server.workflowruns.fakes.ORG
import dev.ostrom.server.workflowruns.fakes.access
import dev.ostrom.server.workflowruns.fakes.session
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

class RunnerChecksTest {
    private val cloud = Uuid.parse("0000c0c0-0000-0000-0000-000000000000")
    private val otherCloud = Uuid.parse("0000c0c1-0000-0000-0000-000000000000")
    private val runs = InMemoryRuns()

    // s-1 runs on launch lch_1, which the cloud session opened; s-2 on a launch of the CLI; s-3 on no launch.
    private val sessions = FakeSessions(
        listOf(session(), session(id = "s-2").copy(launchId = "lch_cli"), session(id = "s-3").copy(launchId = null)),
    )
    private val report = ReportRunnerCheckUseCaseImpl(
        sessions,
        FakeLaunches(mapOf("lch_1" to cloud)),
        ReportCheckUseCaseImpl(access(sessions), runs, FixedClock()),
    )

    private fun request(sessionId: String? = "s-1") = ReportCheckRequest(
        sessionId = sessionId,
        digest = "sha256:pkg",
        nodeId = "n-check",
        scriptId = "sc-1",
        scriptSha = "sha-good",
        exit = 0,
        outcome = CheckOutcome.PASSED,
        durationMs = 1200,
        head = "abcdef0123456789",
        cached = false,
        sandbox = "ostrom-runner",
        outputTail = "ok",
    )

    @Test
    fun aRunTheCloudSessionsRunnerReportsIsTheRunnersInTheReceipt() = runTest {
        val run = report(ORG, cloud, request())
        assertEquals("s-1" to "ada", run.sessionId to run.dev, "Recorded as the session's agent's, for its person")
        assertEquals(listOf(run), runs.checks)
        assertEquals(mapOf(run.id to CheckAttestation.ISOLATED_RUNNER), runs.attestations(ORG, "s-1"))
    }

    @Test
    fun aRunnerReportsForTheSessionsOfItsOwnLaunchesOnly() = runTest {
        assertFailsWith<RunSessionNotFoundException>("Another cloud session's") { report(ORG, otherCloud, request()) }
        assertFailsWith<RunSessionNotFoundException>("A launch of the CLI") { report(ORG, cloud, request("s-2")) }
        assertFailsWith<RunSessionNotFoundException>("A session of no launch") { report(ORG, cloud, request("s-3")) }
        assertFailsWith<RunSessionNotFoundException>("An unknown session") { report(ORG, cloud, request("s-9")) }
        assertFailsWith<RunSessionNotFoundException>("Another organization") { report(Uuid.random(), cloud, request()) }
        assertFailsWith<InvalidRunException>("No session named") { report(ORG, cloud, request(null)) }
        assertEquals(emptyList(), runs.checks)
    }
}
