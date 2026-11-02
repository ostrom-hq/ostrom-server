// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.workflowruns

import dev.ostrom.core.api.models.CheckAttestation
import dev.ostrom.core.api.models.CheckRun
import dev.ostrom.core.api.models.PackageMode
import dev.ostrom.server.gates.domain.models.CiCheck
import dev.ostrom.server.gates.domain.models.GatePullRequest
import dev.ostrom.server.gates.domain.models.GatePush
import dev.ostrom.server.gates.domain.models.GateSubject
import dev.ostrom.server.gates.domain.models.PushRef
import dev.ostrom.server.gates.domain.services.CiChecks
import dev.ostrom.server.gates.domain.services.GateCheckProof
import dev.ostrom.server.workflowruns.fakes.InMemoryRuns
import dev.ostrom.server.workflowruns.fakes.ORG
import dev.ostrom.server.workflowruns.infrastructure.RunsGateCheckProofs
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/** The proof of a check a rule's gate needs attested: the runner's run or the CI's, never the workstation's word. */
class RunsGateCheckProofsTest {
    private val runs = InMemoryRuns()
    private val ci = FakeCi()
    private val proofs = RunsGateCheckProofs(runs) { ci }
    private val push = GateSubject(
        repo = "acme/api",
        actionClass = "push-main",
        command = "git push origin main",
        scope = "session:s-1",
        push = GatePush("origin", listOf(PushRef("refs/heads/main", SHA_A), PushRef("refs/heads/old", "")), false),
    )

    private class FakeCi : CiChecks {
        val conclusions = mutableMapOf<String, String>()
        val asked = mutableListOf<Triple<String, String, String>>()

        override suspend fun latest(orgId: Uuid, repo: String, sha: String, name: String): CiCheck? {
            asked += Triple(repo, sha, name)
            return conclusions[sha]?.let { CiCheck(1, name, sha, it, "github-actions", null, null) }
        }
    }

    private suspend fun ran(id: String, exit: Int?, attestation: CheckAttestation, node: String = "tests") =
        runs.addCheck(
            ORG,
            CheckRun(
                id = id,
                at = "2026-11-02T20:00:00.000Z",
                sessionId = "s-1",
                digest = "sha256:pkg",
                nodeId = node,
                scriptId = "sc-1",
                mode = PackageMode.ENFORCE,
                exit = exit,
                dev = "ada",
            ),
            attestation,
        )

    @Test
    fun theLastRunTheWorkstationReportedIsOnlyDeclared() = runTest {
        ran("chk_1", exit = 0, CheckAttestation.ISOLATED_RUNNER)
        ran("chk_2", exit = 0, CheckAttestation.WORKSTATION_DECLARED)
        ran("chk_3", exit = 1, CheckAttestation.ISOLATED_RUNNER, node = "lint")
        assertEquals(GateCheckProof(CheckAttestation.WORKSTATION_DECLARED, true), proofs.of(ORG, "s-1", push, "tests"))
        assertEquals(listOf(Triple("acme/api", SHA_A, "tests")), ci.asked, "The deleted ref has no commit to prove")
    }

    @Test
    fun theRunnersPassedRunIsAttestedAndItsFailedRunIsNot() = runTest {
        ran("chk_1", exit = 0, CheckAttestation.ISOLATED_RUNNER)
        assertEquals(GateCheckProof(CheckAttestation.ISOLATED_RUNNER, true), proofs.of(ORG, "s-1", push, "tests"))
        assertEquals(emptyList<Triple<String, String, String>>(), ci.asked, "The runner's run is enough")
        ran("chk_2", exit = 1, CheckAttestation.ISOLATED_RUNNER)
        assertEquals(GateCheckProof(CheckAttestation.ISOLATED_RUNNER, false), proofs.of(ORG, "s-1", push, "tests"))
        ran("chk_3", exit = null, CheckAttestation.WORKSTATION_DECLARED)
        assertEquals(GateCheckProof(CheckAttestation.WORKSTATION_DECLARED, false), proofs.of(ORG, "s-1", push, "tests"))
    }

    @Test
    fun theCisSuccessfulRunOnEveryCommitIsAttested() = runTest {
        ran("chk_1", exit = 0, CheckAttestation.WORKSTATION_DECLARED)
        ci.conclusions[SHA_A] = "success"
        assertEquals(GateCheckProof(CheckAttestation.CI, true), proofs.of(ORG, "s-1", push, "tests"))
        ci.conclusions[SHA_A] = "failure"
        assertEquals(GateCheckProof(CheckAttestation.WORKSTATION_DECLARED, true), proofs.of(ORG, "s-1", push, "tests"))

        // The GitHub check asks outside a session: the pull request's head, in the repository the session runs in.
        ci.conclusions[SHA_B] = "success"
        val merge = push.copy(realRepo = "acme/real", push = null, pullRequest = GatePullRequest(6, SHA_B, "main"))
        assertEquals(GateCheckProof(CheckAttestation.CI, true), proofs.of(ORG, null, merge, "tests"))
        assertEquals(Triple("acme/real", SHA_B, "tests"), ci.asked.last())
    }

    @Test
    fun nothingRanItOrNothingCanProveIt() = runTest {
        val none = GateCheckProof(attestation = null, passed = false)
        assertEquals(none, proofs.of(ORG, "s-1", push, "tests"))
        assertEquals(none, proofs.of(ORG, null, push.copy(repo = null), "tests"), "No repository to read the CI of")
        assertEquals(none, proofs.of(ORG, null, push.copy(push = null), "tests"), "No commit")
        val deleted = push.copy(push = GatePush("origin", listOf(PushRef("refs/heads/old", "")), false))
        assertEquals(none, proofs.of(ORG, null, deleted, "tests"), "A deleted ref proves nothing")
    }

    private companion object {
        const val SHA_A = "0123456789abcdef0123456789abcdef01234567"
        const val SHA_B = "fedcba9876543210fedcba9876543210fedcba98"
    }
}
