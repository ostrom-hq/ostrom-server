// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.workflowruns

import dev.ostrom.core.api.models.CheckOutcome
import dev.ostrom.core.api.models.PackageMode
import dev.ostrom.core.api.models.ReportCheckRequest
import dev.ostrom.server.gates.domain.models.ActorKind
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.kernel.errors.InvalidPageException
import dev.ostrom.server.sessions.domain.models.NotYourSessionException
import dev.ostrom.server.sessions.domain.models.SessionClosedException
import dev.ostrom.server.workflowruns.domain.models.InvalidRunException
import dev.ostrom.server.workflowruns.domain.models.NotInPackageException
import dev.ostrom.server.workflowruns.domain.models.RunPackageMismatchException
import dev.ostrom.server.workflowruns.domain.models.RunPackageNotFoundException
import dev.ostrom.server.workflowruns.domain.models.RunSessionNotFoundException
import dev.ostrom.server.workflowruns.domain.repositories.RunQuery
import dev.ostrom.server.workflowruns.domain.usecases.ListChecksUseCaseImpl
import dev.ostrom.server.workflowruns.domain.usecases.ReportCheckUseCaseImpl
import dev.ostrom.server.workflowruns.fakes.ADA
import dev.ostrom.server.workflowruns.fakes.AGENT
import dev.ostrom.server.workflowruns.fakes.BOB
import dev.ostrom.server.workflowruns.fakes.FakeLaunches
import dev.ostrom.server.workflowruns.fakes.FakePackages
import dev.ostrom.server.workflowruns.fakes.FakeSessions
import dev.ostrom.server.workflowruns.fakes.FixedClock
import dev.ostrom.server.workflowruns.fakes.InMemoryRuns
import dev.ostrom.server.workflowruns.fakes.ORG
import dev.ostrom.server.workflowruns.fakes.OTHER_AGENT
import dev.ostrom.server.workflowruns.fakes.PACKAGE
import dev.ostrom.server.workflowruns.fakes.access
import dev.ostrom.server.workflowruns.fakes.envelope
import dev.ostrom.server.workflowruns.fakes.session
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ChecksTest {
    private val runs = InMemoryRuns()
    private val report = ReportCheckUseCaseImpl(access(), runs, FixedClock())
    private val list = ListChecksUseCaseImpl(access(), runs)

    private fun request(
        sessionId: String? = "s-1",
        scriptSha: String? = "sha-good",
        exit: Int? = 0,
        durationMs: Long? = 1200,
        outputTail: String? = "ok",
    ) = ReportCheckRequest(
        sessionId = sessionId,
        digest = "sha256:pkg",
        nodeId = "n-check",
        scriptId = "sc-1",
        scriptSha = scriptSha,
        exit = exit,
        outcome = CheckOutcome.PASSED,
        durationMs = durationMs,
        head = "abcdef0123456789",
        cached = false,
        sandbox = "bwrap",
        outputTail = outputTail,
    )

    @Test
    fun aRunIsRecordedForTheSessionsPersonWithThePackagesModeAndVersion() = runTest {
        val run = report(AGENT, request())
        assertTrue(Regex("run_[0-9a-f]{32}").matches(run.id), run.id)
        assertEquals("2026-10-30T10:05:00.123Z", run.at)
        assertEquals(
            listOf("s-1", "sha256:pkg", "v3", "n-check", "sc-1"),
            listOf(run.sessionId, run.digest, run.version, run.nodeId, run.scriptId),
        )
        assertEquals(PackageMode.ENFORCE, run.mode)
        assertEquals(false, run.tampered)
        assertEquals("ada" to "acme/api", run.dev to run.repo, "The session's person and repository")
        assertEquals(listOf(run), runs.checks)
    }

    @Test
    fun aScriptWhoseBodyIsNotThePackagesIsMarkedTampered() = runTest {
        assertEquals(true, report(AGENT, request(scriptSha = "sha-other")).tampered)
        assertEquals(false, report(AGENT, request(scriptSha = null)).tampered, "Not reported: not judged")
    }

    @Test
    fun theOutputKeepsItsEndAndAMissingOneStaysMissing() = runTest {
        val long = "x".repeat(5000) + "END"
        assertEquals(4000, report(AGENT, request(outputTail = long)).outputTail?.length)
        assertTrue(report(AGENT, request(outputTail = long)).outputTail!!.endsWith("END"))
        assertNull(report(AGENT, request(outputTail = null, durationMs = null)).outputTail)
    }

    @Test
    fun theOutputsSecretsAreMasked() = runTest {
        val output = "push https://ada:hunter2@github.com/acme/api failed; GITHUB_TOKEN=ghp_x"
        val run = report(AGENT, request(outputTail = output))
        assertEquals("push https://***@github.com/acme/api failed; GITHUB_TOKEN=***", run.outputTail)
    }

    @Test
    fun anOutcomeThatContradictsItsExitIsRefused() = runTest {
        val failed = request(exit = 1).copy(outcome = CheckOutcome.FAILED)
        assertEquals(1, report(AGENT, failed).exit)
        assertNull(report(AGENT, request(exit = null).copy(outcome = CheckOutcome.FAILED)).exit, "Not run")
        assertNull(report(AGENT, request(exit = null).copy(outcome = CheckOutcome.TIMEOUT)).exit)
        val shadow = request(exit = null).copy(outcome = CheckOutcome.SKIPPED, scriptId = "sc-2")
        assertEquals(PackageMode.SHADOW, report(AGENT, shadow).mode)
        val contradictions = listOf(
            request(exit = 1),
            request(exit = null),
            request(exit = 0).copy(outcome = CheckOutcome.FAILED),
            request(exit = 0).copy(outcome = CheckOutcome.TIMEOUT),
            request(exit = null).copy(outcome = CheckOutcome.SKIPPED),
            shadow.copy(exit = 0),
            request().copy(outcome = CheckOutcome.UNKNOWN),
        )
        contradictions.forEach { assertFailsWith<InvalidRunException>(it.toString()) { report(AGENT, it) } }
        assertEquals(4, runs.checks.size)
    }

    @Test
    fun anEndedSessionOrAnotherPackageTakesNoRun() = runTest {
        val ended = ReportCheckUseCaseImpl(access(FakeSessions(listOf(session(ended = true)))), runs, FixedClock())
        assertFailsWith<SessionClosedException> { ended(AGENT, request()) }
        val pinned = FakeSessions(listOf(session(packageDigest = "sha256:started")))
        val other = ReportCheckUseCaseImpl(access(pinned), runs, FixedClock())
        assertFailsWith<RunPackageMismatchException> { other(AGENT, request()) }
        val same = FakeSessions(listOf(session(packageDigest = "sha256:pkg")))
        assertEquals("sha256:pkg", ReportCheckUseCaseImpl(access(same), runs, FixedClock())(AGENT, request()).digest)
        assertEquals(1, runs.checks.size)
    }

    @Test
    fun aSessionReportsOnlyUnderThePackageOfItsLaunchsRepository() = runTest {
        // AUDIT-SECU-RUNNER-2 R3: the package decides which checks judge the session, so the agent does not choose it.
        fun reporting(launchRepo: String? = null, sessionRepo: String? = "acme/api", packRepo: String? = "acme/api") =
            ReportCheckUseCaseImpl(
                access(
                    FakeSessions(listOf(session(repo = sessionRepo))),
                    FakePackages(listOf(PACKAGE.copy(envelope = envelope(repo = packRepo)))),
                    FakeLaunches(repos = launchRepo?.let { mapOf("lch_1" to it) }.orEmpty()),
                ),
                runs,
                FixedClock(),
            )
        assertEquals("s-1", reporting(launchRepo = "https://github.com/Acme/api.git")(AGENT, request()).sessionId)
        assertFailsWith<RunPackageMismatchException>("The launch's repository wins over the session's") {
            reporting(launchRepo = "acme/docs")(AGENT, request())
        }
        assertFailsWith<RunPackageMismatchException>("Another repository's package") {
            reporting(sessionRepo = "acme/docs")(AGENT, request())
        }
        assertFailsWith<RunPackageMismatchException>("An organization-wide package") {
            reporting(packRepo = null)(AGENT, request())
        }
        assertEquals("s-1", reporting(sessionRepo = "")(AGENT, request()).sessionId, "A session naming no repository")
        // A person's own session, of no launch: the repository it declares.
        val own = FakeSessions(listOf(session(repo = "acme/docs").copy(launchId = null)))
        assertFailsWith<RunPackageMismatchException> {
            ReportCheckUseCaseImpl(access(own), runs, FixedClock())(ADA, request())
        }
        assertEquals(2, runs.checks.size)
    }

    @Test
    fun aPersonReportsOutsideASessionAsThemselvesInThePackagesRepository() = runTest {
        val run = report(BOB, request(sessionId = null))
        assertEquals("bob" to "acme/api", run.dev to run.repo)
        assertNull(run.sessionId)
        val noRepo = FakeSessions(listOf(session(repo = null)))
        val inPackage = ReportCheckUseCaseImpl(access(noRepo), runs, FixedClock())(AGENT, request())
        assertEquals("ada" to "acme/api", inPackage.dev to inPackage.repo, "A session without a repository")
    }

    @Test
    fun onlyTheSessionsLaunchAndPersonReportForIt() = runTest {
        assertFailsWith<NotYourSessionException> { report(OTHER_AGENT, request()) }
        assertFailsWith<NotYourSessionException> { report(BOB, request()) }
        assertFailsWith<NotYourSessionException> { report(AGENT, request(sessionId = null)) }
        val nobody = GateActor("svc", ORG, ActorKind.SERVICE)
        assertFailsWith<NotYourSessionException> { report(nobody, request(sessionId = null)) }
        assertFailsWith<RunSessionNotFoundException> { report(AGENT, request(sessionId = "s-9")) }
        assertTrue(runs.checks.isEmpty())
    }

    @Test
    fun aRunOfAnUnknownPackageOrScriptIsRefused() = runTest {
        assertFailsWith<RunPackageNotFoundException> { report(AGENT, request().copy(digest = "sha256:other")) }
        assertFailsWith<NotInPackageException> { report(AGENT, request().copy(scriptId = "sc-9")) }
        assertFailsWith<NotInPackageException> { report(AGENT, request().copy(nodeId = "n-notify")) }
        assertTrue(runs.checks.isEmpty())
    }

    @Test
    fun aSessionNeverReportsUnderAnOlderVersionOfItsRepositorysPackage() = runTest {
        // AUDIT-SECU-RUNNER-2 R3b: the pinned package, or the latest when the session started, and no other.
        val older = ReportCheckUseCaseImpl(
            access(packages = FakePackages(replaced = setOf(PACKAGE.digest))),
            runs,
            FixedClock(),
        )
        assertFailsWith<RunPackageMismatchException> { older(AGENT, request()) }
        assertTrue(runs.checks.isEmpty())
        assertEquals("s-1", report(AGENT, request()).sessionId, "The package its repository serves")
    }

    @Test
    fun aMalformedReportIsRefused() = runTest {
        assertFailsWith<InvalidRunException> { report(AGENT, request(durationMs = -1)) }
        assertFailsWith<InvalidRunException> { report(AGENT, request().copy(head = "a\nb")) }
        assertFailsWith<InvalidRunException> { report(AGENT, request().copy(sandbox = "s".repeat(201))) }
        assertFailsWith<InvalidRunException> { report(AGENT, request(sessionId = "s".repeat(201))) }
        assertEquals("0", report(AGENT, request(durationMs = 0)).durationMs.toString())
    }

    @Test
    fun theRunsAreReadByAMemberAndByTheSessionsAgentOnly() = runTest {
        val run = report(AGENT, request())
        val bySession = RunQuery(sessionId = "s-1")
        assertTrue(list(ADA, RunQuery(before = run.id)).isEmpty(), "After the last run")
        assertFailsWith<InvalidPageException> { list(ADA, RunQuery(before = "nope")) }
        assertEquals(1, list(BOB, bySession).size, "Any member reads them")
        assertEquals(1, list(ADA, RunQuery()).size)
        assertEquals(1, list(AGENT, bySession).size)
        assertTrue(list(AGENT, RunQuery()).isEmpty(), "An agent names its own session")
        assertFailsWith<RunSessionNotFoundException> { list(OTHER_AGENT, bySession) }
        assertFailsWith<RunSessionNotFoundException> { list(BOB, RunQuery(sessionId = "s-9")) }
    }

    @Test
    fun aRevokedPackageStillTakesTheRunsOfItsSessions() = runTest {
        val revoked = PACKAGE.copy(envelope = envelope(revoked = true))
        val reportRevoked = ReportCheckUseCaseImpl(access(packages = FakePackages(listOf(revoked))), runs, FixedClock())
        assertFalse(reportRevoked(AGENT, request()).id.isEmpty())
    }
}
