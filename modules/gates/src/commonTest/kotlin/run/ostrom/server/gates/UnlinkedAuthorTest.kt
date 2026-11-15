// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gates

import run.ostrom.server.gates.domain.models.Answer
import run.ostrom.server.gates.domain.models.BoundUnlockRequiredException
import run.ostrom.server.gates.domain.models.GateStatus
import run.ostrom.server.gates.domain.models.GitHubLinkRequiredException
import run.ostrom.server.gates.domain.models.SelfApprovalPolicy
import run.ostrom.server.gates.domain.models.SelfApprovalRefusedException
import run.ostrom.server.gates.domain.models.Viewer
import run.ostrom.server.gates.domain.models.ViewerChannel
import run.ostrom.server.gates.fakes.GateBench
import run.ostrom.server.gates.fakes.user
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
        assertEquals("github_link_required", rights.reasonType, "the key a console reads to ask for the link")
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
        val request = opened.requests.first { it.approverId == "rev-b" }.id
        assertEquals("self_approval_refused", bench.view(user("rev-b"), console, request).rights.reasonType)
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
        // Once known, the unlinked author stays: a later ask that does not carry it never erases it.
        assertEquals(hook.gate.id, bench.open(bench.mergeRequest()).gate.id)
        assertEquals(AUTHOR, bench.repository.gates.getValue(hook.gate.id).unlinkedAuthor)
        assertFailsWith<GitHubLinkRequiredException> { bench.answer(joined, "rev-b") }
    }

    // Q-APP-STEPUP-RISK with the GitHub links read between two passes: the first pass ends with nothing written and
    // asks the unlock nothing; the unlock is used once, by the yes, as on main. A yes the second pass refuses has used
    // it, as any refused yes does (E2E-AUTH-008 (3)): the app unlocks again for the next one.
    @Test
    fun aYesFromThePhoneThatNeedsTheLinksUsesItsUnlockOnce() = runTest {
        val bench = never()
        val opened = bench.unlinkedPullRequest()
        val request = opened.requests.first { it.approverId == "rev-b" }.id.toString()
        val phone = user("rev-b").copy(phoneCredential = "osm-1")

        bench.gitHub.ids["rev-b"] = AUTHOR
        bench.unlocks.unlock("osm-1", request)
        assertFailsWith<SelfApprovalRefusedException> { bench.answer(opened, "rev-b", actor = phone) }
        assertEquals(listOf("osm-1"), bench.unlocks.asked, "one use for the two passes")
        assertEquals(GateStatus.PENDING, bench.repository.gates.getValue(opened.gate.id).status, "nothing written")
        bench.gitHub.ids["rev-b"] = OTHER
        assertFailsWith<BoundUnlockRequiredException>("the refused yes used the unlock") {
            bench.answer(opened, "rev-b", actor = phone)
        }

        bench.unlocks.unlock("osm-1", request)
        assertEquals(GateStatus.APPROVED, bench.answer(opened, "rev-b", actor = phone).gate.status)
        assertEquals(listOf("osm-1", "osm-1", "osm-1"), bench.unlocks.asked)
    }

    private companion object {
        const val AUTHOR = 42L
        const val OTHER = 7L
    }
}
