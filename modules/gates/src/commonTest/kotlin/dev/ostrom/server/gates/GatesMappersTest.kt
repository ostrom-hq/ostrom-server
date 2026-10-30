// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gates

import dev.ostrom.server.gates.domain.models.AnswerChannel
import dev.ostrom.server.gates.domain.models.ApprovalRequest
import dev.ostrom.server.gates.domain.models.ApprovalView
import dev.ostrom.server.gates.domain.models.AskedVia
import dev.ostrom.server.gates.domain.models.Gate
import dev.ostrom.server.gates.domain.models.GateMode
import dev.ostrom.server.gates.domain.models.GateNode
import dev.ostrom.server.gates.domain.models.GatePullRequest
import dev.ostrom.server.gates.domain.models.GateStatus
import dev.ostrom.server.gates.domain.models.GateSubject
import dev.ostrom.server.gates.domain.models.GateView
import dev.ostrom.server.gates.domain.models.InvalidGateRequestException
import dev.ostrom.server.gates.domain.models.RequestStatus
import dev.ostrom.server.gates.domain.models.SelfApprovalPolicy
import dev.ostrom.server.gates.domain.usecases.isSha
import dev.ostrom.server.gates.domain.usecases.viewOf
import dev.ostrom.server.gates.fakes.ORG
import dev.ostrom.server.gates.fakes.agent
import dev.ostrom.server.gates.fakes.user
import dev.ostrom.server.gates.presentation.GateAskRequest
import dev.ostrom.server.gates.presentation.GatePush
import dev.ostrom.server.gates.presentation.GatePushRef
import dev.ostrom.server.gates.presentation.toApproval
import dev.ostrom.server.gates.presentation.toAskGate
import dev.ostrom.server.gates.presentation.toResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid
import dev.ostrom.server.gates.presentation.GatePullRequest as PullRequestDto

/** The contract's view of a gate and of an approval, for every shape a gate can take. */
class GatesMappersTest {
    private val at = Instant.parse("2026-10-30T02:00:00Z")

    private fun gate(
        subject: GateSubject = GateSubject(repo = null, actionClass = "deploy", command = "make deploy", scope = "s"),
        node: GateNode? = null,
        status: GateStatus = GateStatus.PENDING,
        askedBy: List<String> = emptyList(),
        askedVia: Set<AskedVia> = setOf(AskedVia.MCP),
    ) = Gate(
        id = Uuid.random(),
        orgId = ORG,
        key = "k",
        subject = subject,
        node = node,
        label = "Deploy",
        mode = GateMode.ENFORCED,
        status = status,
        askedBy = askedBy,
        askedVia = askedVia,
        sessionIds = emptyList(),
        shownDigest = "d",
        evidence = emptyList(),
        decidedBy = "rev-b".takeIf { status != GateStatus.PENDING },
        decidedAt = at.takeIf { status != GateStatus.PENDING },
        createdAt = at,
    )

    private fun request(gate: Gate, approver: String, status: RequestStatus = RequestStatus.PENDING) = ApprovalRequest(
        id = Uuid.random(),
        gateId = gate.id,
        orgId = ORG,
        approverId = approver,
        status = status,
        channel = AnswerChannel.SLACK.takeIf { status == RequestStatus.APPROVED },
        createdAt = at,
    )

    @Test
    fun aHookRequestWithoutCommandPushOrPullRequestIsReadAsSuch() {
        val bare = GateAskRequest(cls = "deploy").toAskGate()
        assertEquals("", bare.command)
        assertNull(bare.push)
        assertNull(bare.pullRequest)
        val push = GatePush("origin", listOf(GatePushRef("refs/heads/main", "a".repeat(40))))
        val asked = GateAskRequest(cls = "merge", push = push, pr = PullRequestDto("#6", "b".repeat(40))).toAskGate()
        assertEquals(false, asked.push?.force, "no force flag is no force")
        assertEquals(true, GateAskRequest(cls = "x", push = push.copy(force = true)).toAskGate().push?.force)
        assertEquals(false, GateAskRequest(cls = "x", push = push.copy(force = false)).toAskGate().push?.force)
        assertEquals(6, asked.pullRequest)
        assertFailsWith<InvalidGateRequestException> {
            GateAskRequest(cls = "merge", pr = PullRequestDto("six", "b".repeat(40))).toAskGate()
        }
    }

    @Test
    fun aGateWithoutNodeRepositoryOrAskerReadsPlainly() {
        val bare = gate()
        val view = GateView(bare, listOf(request(bare, "rev-b")), listOf("rev-b"))
        val forAgent = view.toResponse(agent())
        assertNull(forAgent.approval, "an agent has no approval of its own")
        assertNull(forAgent.gate.gate)
        assertEquals("", forAgent.gate.dev)
        assertNull(forAgent.gate.push)
        assertNull(forAgent.gate.ci)
        assertNull(forAgent.gate.decidedAt)
        val approval = ApprovalView(view.requests.single(), bare).toApproval()
        assertEquals("An agent: Deploy", approval.title)
        assertNull(approval.sha)
        assertNull(approval.workflowId)
        assertEquals("console", approval.channel)
    }

    @Test
    fun aDecidedPullRequestGateOfTheCiShowsItsHeadAndItsChannel() {
        val pullRequest = GatePullRequest(6, "c".repeat(40), "main")
        val node = GateNode("w1", "g1", "Merge into main", listOf("rev-b"), enforced = true)
        val merged = gate(
            subject = GateSubject(
                repo = "acme/api",
                realRepo = "acme/api-real",
                actionClass = "merge",
                command = "gh pr merge 6",
                scope = "s",
                pullRequest = pullRequest,
                commits = listOf("c".repeat(40)),
            ),
            node = node,
            status = GateStatus.RESERVED,
            askedBy = listOf("lyes"),
            askedVia = setOf(AskedVia.CI, AskedVia.HOOK),
        )
        val answered = request(merged, "rev-b", RequestStatus.APPROVED)
        val response = GateView(merged, listOf(answered), emptyList()).toResponse(user("rev-b"))
        assertEquals("approved", response.gate.status, "a reserved yes reads approved")
        assertEquals("w1", response.gate.gate?.workflowId)
        assertEquals("c".repeat(40), response.gate.ci?.sha)
        assertEquals(listOf("ci", "hook"), response.gate.askedVia)
        assertEquals(at.toString(), response.gate.decidedAt)
        val approval = response.approval!!
        assertEquals("lyes: Deploy in acme/api-real", approval.title)
        assertEquals("c".repeat(40), approval.sha)
        assertEquals("slack", approval.channel)
        assertEquals("w1", approval.workflowId)
        val other = GateView(merged, listOf(answered), emptyList()).toResponse(user("rev-c"))
        assertNull(other.approval, "a person who is not an approver has none")
        val closed = ApprovalView(request(merged, "rev-c", RequestStatus.CLOSED), merged).toApproval()
        assertEquals("expired", closed.status)
        val local = merged.copy(subject = merged.subject.copy(realRepo = null, pullRequest = null), askedVia = setOf())
        val pushed = ApprovalView(request(local, "rev-b"), local).toApproval()
        assertEquals("lyes: Deploy in acme/api", pushed.title)
        assertEquals("c".repeat(40), pushed.sha, "the last commit of a push")
        assertNull(GateView(local, emptyList(), emptyList()).toResponse(user("rev-b")).gate.ci)
        val hooked = merged.copy(askedVia = setOf(AskedVia.HOOK))
        val fromHook = GateView(hooked, emptyList(), emptyList()).toResponse(user("rev-b")).gate
        assertNull(fromHook.ci, "a pull request asked by a hook only has no CI context")
        assertEquals("6", fromHook.pr?.number)
    }

    @Test
    fun aPendingGateWaitsOnlyForTheApproversStillPending() {
        val pending = gate(askedBy = listOf("lyes"))
        val requests = listOf(request(pending, "rev-b"), request(pending, "rev-c", RequestStatus.CLOSED))
        assertEquals(listOf("rev-b"), viewOf(pending, requests, SelfApprovalPolicy.NEVER).waitingFor)
        val decided = pending.copy(status = GateStatus.REJECTED)
        assertEquals(emptyList(), viewOf(decided, requests, SelfApprovalPolicy.NEVER).waitingFor)
    }

    @Test
    fun aCommitIdIsFortyOrSixtyFourLowerCaseHexDigits() {
        assertTrue(isSha("0123456789abcdef".repeat(2) + "01234567"))
        assertTrue(isSha("f".repeat(64)))
        val refusals =
            listOf("0".repeat(39), "/".repeat(40), ":".repeat(40), "A".repeat(40), "g".repeat(40), "`".repeat(64))
        for (refused in refusals) {
            assertFalse(isSha(refused), refused)
        }
    }
}
