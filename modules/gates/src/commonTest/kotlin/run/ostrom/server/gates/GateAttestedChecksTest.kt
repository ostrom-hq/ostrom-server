// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gates

import run.ostrom.core.api.models.CheckAttestation
import run.ostrom.server.gates.domain.models.CheckNotAttestedException
import run.ostrom.server.gates.domain.models.GateStatus
import run.ostrom.server.gates.domain.services.GateCheckNeed
import run.ostrom.server.gates.domain.services.GateCheckProof
import run.ostrom.server.gates.fakes.GateBench
import run.ostrom.server.gates.fakes.SESSION
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A rule's gate that needs a check attested (decision 13:23, core#40): a run the workstation declared fails it. */
class GateAttestedChecksTest {
    private fun bench() = GateBench().apply {
        needs.byNode["g-main"] =
            listOf(GateCheckNeed("tests", attested = true), GateCheckNeed("lint", attested = false))
    }

    @Test
    fun aDeclaredRunIsRefusedAndTheAnswerSaysItIsOnlyDeclared() = runTest {
        val bench = bench()
        bench.proofs.byCheck["tests"] = GateCheckProof(CheckAttestation.WORKSTATION_DECLARED, passed = true)
        val refused = assertFailsWith<CheckNotAttestedException> { bench.open(bench.pushRequest()) }
        assertEquals("check_not_attested", refused.type)
        val declared = "tests was only declared by the workstation (workstation_declared)"
        assertTrue(declared in refused.message, refused.message)
        assertEquals(listOf("tests"), refused.unproven.map { it.first }, "lint needs no attestation")
        assertEquals(listOf<Pair<String?, String>>(SESSION to "tests"), bench.proofs.asked)
        assertTrue(bench.repository.gates.isEmpty(), "No yes is asked before the check")
        assertTrue(bench.notifier.requested.isEmpty())
    }

    @Test
    fun aRunTheWorkstationCouldNotMakeUpOpensTheGate() = runTest {
        listOf(CheckAttestation.ISOLATED_RUNNER, CheckAttestation.CI).forEach { attestation ->
            val bench = bench()
            bench.proofs.byCheck["tests"] = GateCheckProof(attestation, passed = true)
            assertEquals(GateStatus.PENDING, bench.open(bench.pushRequest()).gate.status, attestation.value)
        }
    }

    @Test
    fun anAttestedRunThatFailedOrNoRunAtAllIsRefused() = runTest {
        val bench = bench()
        bench.needs.byNode["g-main"] = listOf(GateCheckNeed("tests", true), GateCheckNeed("e2e", true))
        bench.proofs.byCheck["tests"] = GateCheckProof(CheckAttestation.CI, passed = false)
        val refused = assertFailsWith<CheckNotAttestedException> { bench.open(bench.mergeRequest(viaCheck = true)) }
        assertTrue("tests failed in its last attested run (ci)" in refused.message, refused.message)
        assertTrue("e2e has no run in this session and no CI run on this commit" in refused.message, refused.message)
        assertEquals(listOf<Pair<String?, String>>(null to "tests", null to "e2e"), bench.proofs.asked)
    }

    @Test
    fun aShadowOrUngatedActionNeverAsksForTheChecks() = runTest {
        val bench = bench()
        val shadow = bench.pushRequest(node = bench.node("lyes", enforced = false))
        assertEquals(GateStatus.SHADOW, bench.open(shadow).gate.status)
        assertEquals(GateStatus.UNGATED, bench.open(bench.pushRequest(node = null)).gate.status)
        assertTrue(bench.proofs.asked.isEmpty())
    }
}
