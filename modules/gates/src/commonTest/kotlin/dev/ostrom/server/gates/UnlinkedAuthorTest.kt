// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gates

import dev.ostrom.server.gates.domain.models.Answer
import dev.ostrom.server.gates.domain.models.GateStatus
import dev.ostrom.server.gates.domain.models.GitHubLinkRequiredException
import dev.ostrom.server.gates.domain.models.SelfApprovalPolicy
import dev.ostrom.server.gates.domain.models.SelfApprovalRefusedException
import dev.ostrom.server.gates.domain.models.Viewer
import dev.ostrom.server.gates.domain.models.ViewerChannel
import dev.ostrom.server.gates.fakes.GateBench
import dev.ostrom.server.gates.fakes.user
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Q-GATES-G1 (a): a pull request whose author linked no GitHub account in Ostrom is named by their GitHub login, which
 * may be another person's Ostrom login. Under the `never` policy, its gate waits for an approver who linked a GitHub
 * account other than the author's.
 */
class UnlinkedAuthorTest {
    private val console = Viewer(ViewerChannel.CONSOLE, answers = true)

    private fun never() = GateBench().apply { policies.policy = SelfApprovalPolicy.NEVER }

    // The GitHub check of pull request #6, whose author is GitHub account 42, linked to no Ostrom person.
    private suspend fun GateBench.unlinkedPullRequest() =
        open(mergeRequest(viaCheck = true).copy(asker = "octo", unlinkedAuthor = AUTHOR))

    @Test
    fun anApproverWhoLinkedNoGitHubAccountCannotSayYes() = runTest {
        val bench = never()
        val opened = bench.unlinkedPullRequest()

        assertFailsWith<GitHubLinkRequiredException> { bench.answer(opened, "rev-b") }
        assertEquals(GateStatus.PENDING, bench.repository.gates.getValue(opened.gate.id).status)
        val rights = bench.view(user("rev-b"), console, opened.requests.first { it.approverId == "rev-b" }.id).rights
        assertFalse(rights.canApprove)
        assertEquals(GitHubLinkRequiredException().message, rights.reason)
        assertFalse(bench.queue(user("rev-b"), console).pending.single().rights.canApprove)
    }

    @Test
    fun anApproverWhoLinkedAnotherGitHubAccountSaysYes() = runTest {
        val bench = never()
        bench.gitHub.ids["rev-b"] = OTHER
        val opened = bench.unlinkedPullRequest()

        val request = opened.requests.first { it.approverId == "rev-b" }.id
        assertTrue(bench.view(user("rev-b"), console, request).rights.canApprove)
        assertEquals(GateStatus.APPROVED, bench.answer(opened, "rev-b").gate.status)
    }

    // The author linked their GitHub account to an Ostrom login after the pull request opened: still their own merge.
    @Test
    fun theAuthorUnderAnotherLoginIsStillTheAuthor() = runTest {
        val bench = never()
        bench.gitHub.ids["rev-b"] = AUTHOR
        val opened = bench.unlinkedPullRequest()

        assertFailsWith<SelfApprovalRefusedException> { bench.answer(opened, "rev-b") }
    }

    @Test
    fun aNoNeedsNoLinkAndAnotherPolicyKeepsItsRules() = runTest {
        val bench = never()
        val refused = bench.unlinkedPullRequest()
        assertEquals(GateStatus.REJECTED, bench.answer(refused, "rev-b", Answer.NO).gate.status)
        assertFalse(bench.queue(user("rev-b"), console).recent.single().rights.canApprove, "answered already")

        val sole = GateBench()
        val opened = sole.unlinkedPullRequest()
        assertEquals(GateStatus.APPROVED, sole.answer(opened, "rev-b").gate.status)
    }

    // The hook of the developer's session asks first; the GitHub check joins and brings what it knows of the author.
    @Test
    fun theCheckJoiningTheHooksGateBringsTheUnlinkedAuthor() = runTest {
        val bench = never()
        val hook = bench.open(bench.mergeRequest())
        assertEquals(null, hook.gate.unlinkedAuthor)

        val joined = bench.unlinkedPullRequest()
        assertEquals(hook.gate.id, joined.gate.id)
        assertEquals(AUTHOR, bench.repository.gates.getValue(hook.gate.id).unlinkedAuthor)
        assertFailsWith<GitHubLinkRequiredException> { bench.answer(joined, "rev-b") }
    }

    private companion object {
        const val AUTHOR = 42L
        const val OTHER = 7L
    }
}
