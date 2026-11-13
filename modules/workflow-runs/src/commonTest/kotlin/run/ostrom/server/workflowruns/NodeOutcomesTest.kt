// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.workflowruns

import run.ostrom.core.api.models.CheckAttestation
import run.ostrom.core.api.models.CheckRun
import run.ostrom.core.api.models.Notification
import run.ostrom.core.api.models.NotificationStatus
import run.ostrom.core.api.models.PackageMode
import run.ostrom.core.api.models.ReceiptNode
import run.ostrom.server.compiler.domain.services.PackageTexts
import run.ostrom.server.gates.domain.models.CiCheck
import run.ostrom.server.gates.domain.usecases.SessionGate
import run.ostrom.server.sessions.domain.models.SeenItem
import run.ostrom.server.sessions.domain.models.Session
import run.ostrom.server.workflowruns.domain.models.PackageNode
import run.ostrom.server.workflowruns.domain.usecases.NodeOutcomes
import run.ostrom.server.workflowruns.domain.usecases.NodeProofs
import run.ostrom.server.workflowruns.fakes.PACKAGE
import run.ostrom.server.workflowruns.fakes.gate
import run.ostrom.server.workflowruns.fakes.node
import run.ostrom.server.workflowruns.fakes.session
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class NodeOutcomesTest {
    private fun run(id: String, exit: Int?, head: String? = "abcdef0123456789", sandbox: String? = "bwrap") = CheckRun(
        id = id,
        at = "2026-10-30T10:00:00.000Z",
        sessionId = "s-1",
        digest = "sha256:pkg",
        nodeId = "n-check",
        scriptId = "sc-1",
        mode = PackageMode.ENFORCE,
        exit = exit,
        head = head,
        sandbox = sandbox,
        dev = "ada",
    )

    private fun note(status: NotificationStatus, error: String? = null) = Notification(
        id = "ntf_${status.value}",
        at = "2026-10-30T10:00:00.000Z",
        sessionId = "s-1",
        digest = "sha256:pkg",
        nodeId = "n-notify",
        notifyId = "nt-1",
        channel = "log:team",
        mode = PackageMode.ENFORCE,
        status = status,
        text = "t".repeat(300),
        dev = "ada",
        error = error,
    )

    private fun outcomes(
        nodes: List<PackageNode>,
        runs: List<CheckRun> = emptyList(),
        notes: List<Notification> = emptyList(),
        gates: List<SessionGate> = emptyList(),
        session: Session = session(receiptId = "rcpt_session_s-1"),
    ) = NodeOutcomes(PACKAGE.copy(nodes = nodes), session, runs, notes, gates).nodes()

    /** The node of [node] once [runs] are reported, those of [attestations] by the runner. */
    private fun attested(node: PackageNode, runs: List<CheckRun>, attestations: Map<String, CheckAttestation>) =
        NodeOutcomes(
            PACKAGE.copy(nodes = listOf(node)), session(), runs, emptyList(), emptyList(), NodeProofs(attestations),
        ).nodes().single()

    private fun one(node: PackageNode, runs: List<CheckRun> = emptyList(), notes: List<Notification> = emptyList()) =
        outcomes(listOf(node), runs, notes).single()

    @Test
    fun aCheckSaysWhetherItPassedFailedOrBlockedBeforePassing() {
        val check = node("n-check", "check")
        val passed = one(check, listOf(run("chk_1", 0)))
        assertEquals("passed", passed.outcome)
        assertEquals("1 run, sandbox bwrap, last exit 0 on abcdef01", passed.detail)
        assertEquals("chk_1", passed.ref)
        assertEquals(
            "passed after 1 failure that blocked the action",
            one(check, listOf(run("chk_1", 1), run("chk_2", 0))).outcome,
        )
        assertEquals(
            "passed after 2 failures that blocked the action",
            one(check, listOf(run("chk_1", 1), run("chk_2", null), run("chk_3", 0))).outcome,
        )
        val failed = one(check, listOf(run("chk_1", 0), run("chk_2", 2, head = "", sandbox = null)))
        assertEquals("failed: action blocked (2 runs)", failed.outcome)
        assertEquals("2 runs, sandbox ?, last exit 2", failed.detail)
        assertEquals("1 run, sandbox bwrap, last exit 1", one(check, listOf(run("chk_1", 1, head = null))).detail)
        assertEquals("failed: action blocked (1 run)", one(check, listOf(run("chk_1", null))).outcome, "Not run")
        val rewritten = one(check, listOf(run("chk_1", 0).copy(tampered = true)))
        assertEquals("failed: the script run is not the package's", rewritten.outcome, "Passed on another script")
    }

    @Test
    fun everyCheckNodeIsTheWorkstationsDeclarationAndNoOtherNodeCarriesAnAttestation() {
        val check = node("n-check", "check")
        val shadow = node("n-check", "check", mode = PackageMode.SHADOW)
        val declared = CheckAttestation.WORKSTATION_DECLARED
        assertEquals(declared, one(check, listOf(run("chk_1", 0))).attestation)
        assertEquals(declared, one(shadow, listOf(run("chk_1", 0))).attestation)
        assertEquals(declared, one(check).attestation)
        assertEquals(null, one(node("n-gate", "gate")).attestation)
        assertEquals(null, one(node("n-notify", "notify")).attestation)
    }

    @Test
    fun aCheckTheCiRanOnThatCommitTakesTheCisOutcomeAndAttestation() {
        val url = "https://github.com/r/77"
        val proof = CiCheck(77, "unit", "abcdef0123456789", "success", "github-actions", url, null)
        fun proven(node: PackageNode, runs: List<CheckRun>, check: CiCheck = proof): ReceiptNode {
            val pack = PACKAGE.copy(nodes = listOf(node))
            val ci = mapOf(node.id to check)
            return NodeOutcomes(pack, session(), runs, emptyList(), emptyList(), NodeProofs(ci = ci)).nodes().single()
        }
        val check = node("n-check", "check")
        val passed = proven(check, listOf(run("chk_1", 0)))
        assertEquals(CheckAttestation.CI, passed.attestation)
        assertEquals("passed in CI" to url, passed.outcome to passed.ref)
        val detail = "CI unit by github-actions on abcdef01; 1 run, sandbox bwrap, last exit 0 on abcdef01"
        assertEquals(detail, passed.detail)
        val failed = proven(check, listOf(run("chk_1", 0)), proof.copy(conclusion = "failure", url = null))
        assertEquals("failed in CI (failure)" to "chk_1", failed.outcome to failed.ref)
        // Shadow, or never reported: nothing ran on the workstation's side to compare, the node stays declared.
        val shadow = proven(node("n-check", "check", mode = PackageMode.SHADOW), listOf(run("chk_1", 0)))
        assertEquals(CheckAttestation.WORKSTATION_DECLARED, shadow.attestation)
        assertEquals(CheckAttestation.WORKSTATION_DECLARED, proven(check, emptyList()).attestation)
    }

    @Test
    fun aCheckNodeIsTheRunnersWhenItsLastRunIs() {
        val check = node("n-check", "check")
        val runner = mapOf("chk_2" to CheckAttestation.ISOLATED_RUNNER)
        val byRunner = attested(check, listOf(run("chk_1", 1), run("chk_2", 0)), runner)
        assertEquals(CheckAttestation.ISOLATED_RUNNER, byRunner.attestation)
        assertEquals("chk_2", byRunner.ref)
        val rewritten = attested(check, listOf(run("chk_2", 0).copy(tampered = true)), runner)
        assertEquals(CheckAttestation.WORKSTATION_DECLARED, rewritten.attestation, "Another script proves nothing")
        assertEquals("failed: the script run is not the package's", rewritten.outcome)
        val afterIt = attested(check, listOf(run("chk_2", 0), run("chk_3", 0)), runner)
        assertEquals(CheckAttestation.WORKSTATION_DECLARED, afterIt.attestation, "The last run is the workstation's")
        val shadow = node("n-check", "check", mode = PackageMode.SHADOW)
        val notRun = attested(shadow, listOf(run("chk_2", 0)), runner)
        assertEquals(CheckAttestation.WORKSTATION_DECLARED, notRun.attestation, "A shadow check runs nothing")
    }

    @Test
    fun aShadowCheckSaysItWouldHaveRunAndOneNeverRunIsNotTriggered() {
        val shadow = node("n-check", "check", mode = PackageMode.SHADOW)
        val enforced = node("n-gate", "gate")
        val nodes = outcomes(listOf(shadow, enforced), listOf(run("chk_1", 0), run("chk_2", 1)))
        assertEquals("shadow: would have run 2×, not run", nodes.first().outcome)
        assertEquals("not triggered", one(node("n-check", "check")).outcome)
    }

    @Test
    fun aGateCitesTheSessionsReceiptWhenTheSessionAskedAny() {
        val gateNode = node("n-gate", "gate")
        val asked = outcomes(listOf(gateNode), gates = listOf(gate(true, false), gate(false, false))).single()
        val expected = ReceiptNode(
            "n-gate", "wf1", "gate", "Label n-gate", "2 gate requests in the session", ref = "rcpt_session_s-1",
        )
        assertEquals(expected, asked)
        val once = outcomes(listOf(gateNode), gates = listOf(gate(true, true))).single()
        assertEquals("1 gate request in the session", once.outcome)
        assertEquals("not triggered", one(gateNode).outcome)
    }

    @Test
    fun aNotificationSaysWhereItWentOrWhyNot() {
        val notify = node("n-notify", "notify")
        val sent = one(notify, notes = listOf(note(NotificationStatus.QUEUED), note(NotificationStatus.SENT)))
        assertEquals("sent to log:team", sent.outcome)
        assertEquals(200, sent.detail?.length)
        assertEquals("ntf_sent", sent.ref)
        assertEquals("queued for log:team", one(notify, notes = listOf(note(NotificationStatus.QUEUED))).outcome)
        assertEquals(
            "shadow: logged, not sent (log:team)",
            one(notify, notes = listOf(note(NotificationStatus.LOGGED_ONLY))).outcome,
        )
        assertEquals(
            "not sent (log:team: down)",
            one(notify, notes = listOf(note(NotificationStatus.NOT_SENT, "down"))).outcome,
        )
        assertEquals("not sent (log:team)", one(notify, notes = listOf(note(NotificationStatus.NOT_SENT))).outcome)
        assertEquals("not triggered", one(notify).outcome)
    }

    @Test
    fun aStepSaysOnlyWhetherItsSkillWasOpened() {
        val step = node("n-step", "step", workflow = "Deploy prod")
        val at = Instant.parse("2026-10-30T09:41:07Z")
        fun opened(name: String, kind: String = "skill") = outcomes(
            listOf(node("n-check", "check", workflow = "Deploy prod"), step),
            runs = listOf(run("chk_1", 0)),
            session = session().copy(seen = listOf(SeenItem(kind, name, "hook", at))),
        ).last().outcome
        val skill = "ostrom-wf-${PackageTexts.slug("Ship Deploy prod")}"
        val seen = "skill opened at 09:41 (whether it was followed is not checked)"
        assertEquals(seen, opened(skill))
        assertEquals(seen, opened("plugin:$skill"))
        assertEquals(seen, opened("/repo/.claude/skills/$skill/SKILL.md"))
        assertEquals("delivered as a skill; no sign it was opened", opened("ostrom-wf-other"))
        assertEquals("delivered as a skill; no sign it was opened", opened(skill, kind = "subagent"))
    }

    @Test
    fun agentsTriggersAndUnknownNodesSayWhatTheyAre() {
        val nodes = outcomes(listOf(node("a", "agent"), node("t", "trigger"), node("x", "later")))
        assertEquals(
            listOf("delivered (sub-agent or skill), not reported", "in the session context", "not triggered"),
            nodes.map { it.outcome },
        )
    }

    @Test
    fun theReceiptKeepsTheWorkflowsThatActedOrElseTheEnforcedOnes() {
        val acting = node("n-check", "check", workflow = "wf1")
        val idle = node("n-other", "check", workflow = "wf2")
        val stepOfActing = node("n-step", "step", workflow = "wf1")
        assertEquals(
            listOf("n-check", "n-step"),
            outcomes(listOf(acting, idle, stepOfActing), listOf(run("chk_1", 0))).map { it.id },
        )
        val shadow = node("n-shadow", "agent", workflow = "wf3", mode = PackageMode.SHADOW)
        assertEquals(listOf("n-check", "n-other"), outcomes(listOf(acting, idle, shadow)).map { it.id })
    }
}
