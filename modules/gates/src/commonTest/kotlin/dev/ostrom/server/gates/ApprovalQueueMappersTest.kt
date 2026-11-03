// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gates

import dev.ostrom.server.gates.domain.models.ApprovalInbox
import dev.ostrom.server.gates.domain.models.ApprovalRequest
import dev.ostrom.server.gates.domain.models.ApprovalScreen
import dev.ostrom.server.gates.domain.models.ApprovalView
import dev.ostrom.server.gates.domain.models.AskedVia
import dev.ostrom.server.gates.domain.models.CallerRights
import dev.ostrom.server.gates.domain.models.Gate
import dev.ostrom.server.gates.domain.models.GateMode
import dev.ostrom.server.gates.domain.models.GateNode
import dev.ostrom.server.gates.domain.models.GatePullRequest
import dev.ostrom.server.gates.domain.models.GateStatus
import dev.ostrom.server.gates.domain.models.GateSubject
import dev.ostrom.server.gates.domain.models.RequestStatus
import dev.ostrom.server.gates.domain.models.Viewer
import dev.ostrom.server.gates.domain.models.ViewerChannel
import dev.ostrom.server.gates.fakes.ORG
import dev.ostrom.server.gates.presentation.ApprovalCallerView
import dev.ostrom.server.gates.presentation.ApprovalViewer
import dev.ostrom.server.gates.presentation.ApprovalWhat
import dev.ostrom.server.gates.presentation.ApprovalWho
import dev.ostrom.server.gates.presentation.GateRef
import dev.ostrom.server.gates.presentation.GateScopes
import dev.ostrom.server.gates.presentation.toApproval
import dev.ostrom.server.gates.presentation.toQueue
import dev.ostrom.server.gates.presentation.toVia
import dev.ostrom.server.gates.presentation.toViewResponse
import dev.ostrom.server.gates.presentation.toViewer
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.PrincipalChannel
import dev.ostrom.server.kernel.auth.PrincipalKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid
import dev.ostrom.server.gates.presentation.GatePullRequest as PullRequestDto

/** The contract's `ApprovalQueue` and `ApprovalView`, for every shape of gate and reader. */
class ApprovalQueueMappersTest {
    private val at = Instant.parse("2026-10-30T02:00:00Z")

    private fun gate(subject: GateSubject, node: GateNode?, askedBy: List<String>, sessions: List<String>) = Gate(
        id = Uuid.random(),
        orgId = ORG,
        key = "k",
        subject = subject,
        node = node,
        label = "Merge into main",
        mode = GateMode.ENFORCED,
        status = GateStatus.PENDING,
        askedBy = askedBy,
        askedVia = setOf(AskedVia.HOOK, AskedVia.CI),
        sessionIds = sessions,
        shownDigest = "d",
        evidence = emptyList(),
        createdAt = at,
    )

    private fun screen(gate: Gate, rights: CallerRights) = ApprovalScreen(
        ApprovalView(
            ApprovalRequest(Uuid.random(), gate.id, ORG, "rev-b", RequestStatus.PENDING, createdAt = at),
            gate,
        ),
        rights,
    )

    private fun principal(channel: PrincipalChannel, vararg scopes: String) =
        AuthenticatedPrincipal("rev-b", PrincipalKind.USER, scopes.toSet(), channel = channel)

    @Test
    fun theReaderIsTheConsoleTheAppOrAProgramAsTheCredentialIsHeld() {
        assertEquals(
            Viewer(ViewerChannel.CONSOLE, answers = true),
            principal(PrincipalChannel.BROWSER, GateScopes.ALL_USER).toViewer(),
        )
        assertEquals(
            Viewer(ViewerChannel.APP, answers = true),
            principal(PrincipalChannel.MOBILE_APP, GateScopes.ANSWER).toViewer(),
        )
        assertEquals(
            Viewer(ViewerChannel.CLI, answers = false),
            principal(PrincipalChannel.API_TOKEN, GateScopes.READ).toViewer(),
        )
        assertEquals(listOf("console", "app", "cli"), ViewerChannel.entries.map { it.toVia() })
    }

    @Test
    fun aPushAskedFromAnotherRepositoryShowsBothRepositoriesAndTheGate() {
        val subject = GateSubject(
            repo = "acme/api",
            realRepo = "acme/fork",
            actionClass = "push-main",
            command = "git push origin main",
            scope = "s",
            harnessVersion = "0.9.1",
        )
        val node = GateNode("ship", "g-main", "Merge into main", listOf("lyes", "rev-b"), enforced = true)
        val rights = CallerRights("rev-b", self = false, canApprove = true)
        val shown = screen(gate(subject, node, listOf("lyes"), listOf("claude-5e5")), rights)
        val view = shown.toViewResponse()
        assertEquals(shown.view.toApproval(), view.approval, "the approval as GET /approvals/{id} serves it")
        assertEquals(ApprovalWho(dev = "lyes", session = "claude-5e5"), view.who)
        assertEquals(
            ApprovalWhat("Merge into main", "git push origin main", "acme/fork", "acme/api", "0.9.1"),
            view.what,
        )
        assertEquals(
            GateRef(
                workflowId = "ship",
                workflowName = "ship",
                nodeId = "g-main",
                label = "Merge into main",
                approvers = listOf("lyes", "rev-b"),
                status = "accepted",
                enforce = true,
                basis = "declared",
                reason = "",
            ),
            view.gate,
        )
        assertNull(view.pr)
        assertEquals(listOf("ci", "hook"), view.askedVia)
        assertEquals(ApprovalCallerView("rev-b", self = false, canApprove = true), view.you)
    }

    @Test
    fun aMergeOfTheCheckWithoutCommandNodeOrAskerReadsPlainly() {
        val subject = GateSubject(
            repo = "acme/api",
            actionClass = "merge",
            command = "",
            scope = "s",
            pullRequest = GatePullRequest(6, "c".repeat(40), "main"),
        )
        val rights = CallerRights("lyes", self = true, canApprove = true, selfApproval = true)
        val view = screen(gate(subject, null, emptyList(), emptyList()), rights).toViewResponse()
        assertEquals(ApprovalWho(), view.who)
        assertEquals(ApprovalWhat("Merge into main", repo = "acme/api"), view.what)
        assertNull(view.gate)
        assertEquals(PullRequestDto("6", "c".repeat(40), "main"), view.pr)
        assertEquals(ApprovalCallerView("lyes", self = true, canApprove = true, selfApproval = true), view.you)
        val same = subject.copy(realRepo = "acme/api", command = "gh pr merge 6")
        val local = screen(gate(same, null, emptyList(), emptyList()), rights).toViewResponse().what
        assertEquals(ApprovalWhat("Merge into main", "gh pr merge 6", "acme/api"), local, "the same repository once")
    }

    @Test
    fun theQueueNamesItsReaderAndMapsEachApproval() {
        val subject = GateSubject(repo = null, actionClass = "deploy", command = "make deploy", scope = "s")
        val answered = "This gate was already answered."
        val refused = CallerRights("rev-b", self = false, canApprove = false, reason = answered)
        val pending = screen(gate(subject, null, listOf("lyes"), emptyList()), CallerRights("rev-b", false, true))
        val recent = screen(gate(subject, null, listOf("lyes"), emptyList()), refused)
        val app = Viewer(ViewerChannel.APP, answers = true)
        val queue = ApprovalInbox("rev-b", app, listOf(pending), listOf(recent)).toQueue()
        assertEquals("rev-b", queue.operator)
        assertEquals(ApprovalViewer("rev-b", "app"), queue.you)
        assertEquals(listOf(pending.toViewResponse()), queue.pending)
        assertEquals(listOf(recent.toViewResponse()), queue.recent)
        assertEquals(
            ApprovalCallerView("rev-b", self = false, canApprove = false, reason = "This gate was already answered."),
            queue.recent.single().you,
        )
        assertNull(queue.pending.single().what.repo)
    }

    @Test
    fun theKeyOfARefusedYesReachesTheConsole() {
        val subject = GateSubject(repo = "acme/api", actionClass = "merge", command = "", scope = "s")
        val rights = CallerRights("rev-b", false, false, "Link your GitHub account.", reasonType = "github_link_required")
        val you = screen(gate(subject, null, listOf("lyes"), emptyList()), rights).toViewResponse().you
        assertEquals(
            ApprovalCallerView("rev-b", false, false, "Link your GitHub account.", reasonType = "github_link_required"),
            you,
        )
    }
}
