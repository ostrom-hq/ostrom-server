// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.github

import dev.ostrom.server.gates.domain.models.GatePullRequest
import dev.ostrom.server.gates.domain.models.GateStatus
import dev.ostrom.server.gates.domain.models.RequestStatus
import dev.ostrom.server.integrations.github.domain.GitHubGateChecks
import dev.ostrom.server.integrations.github.domain.GitHubInstallation
import dev.ostrom.server.integrations.github.domain.GitHubPull
import dev.ostrom.server.integrations.github.domain.GitHubPullRequestHeads
import dev.ostrom.server.kernel.logging.LogLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class GitHubGateChecksTest {
    private val repository = MemoryGitHubRepository()
    private val api = FakeGitHubApi()
    private val clock = SettableClock()
    private val checks = GitHubGateChecks(repository, api, "https://console.example/", clock)

    private suspend fun installed() = repository.saveInstallation(GitHubInstallation(ORG, 42, "acme"), NOW)

    @Test
    fun theCheckWaitsThenTurnsGreenOnTheYesOnTheSameRun() = runTest {
        installed()
        val requests = listOf(request("dev"), request("lead"), request("ops"))
        checks.requested(mergeGate(), requests)
        val waiting = api.published.single()
        assertEquals("acme/api", waiting.first)
        assertNull(waiting.third, "A new check run")
        with(waiting.second) {
            assertEquals("in_progress" to null, status to conclusion)
            assertEquals("Waiting for a yes from lead or ops", title)
            assertEquals(SHA, headSha)
            assertEquals("https://console.example/yes/${mergeGate().id}", detailsUrl)
            assertEquals(mergeGate().id.toString(), externalId)
        }

        checks.decided(mergeGate(GateStatus.APPROVED), requests)
        val approved = api.published.last()
        assertEquals(100L, approved.third, "The same check run, updated")
        assertEquals("completed" to "success", approved.second.status to approved.second.conclusion)
        assertEquals("Approved by lead", approved.second.title)
        assertEquals("Gate \"Merge into main\". Signed receipt rcpt_1.", approved.second.summary)
        assertEquals("success", repository.check(ORG, mergeGate().id)?.conclusion)
    }

    @Test
    fun everyStateHasItsConclusion() = runTest {
        installed()
        val sole = listOf(request("dev"))
        val cases = listOf(
            mergeGate(GateStatus.PENDING) to "Waiting for a yes from dev",
            mergeGate(GateStatus.RESERVED).copy(selfApproved = true) to
                "Approved by lead (self-approved: sole approver)",
            mergeGate(GateStatus.CONSUMED) to "Approved by lead",
            mergeGate(GateStatus.REJECTED) to "Refused by lead",
            mergeGate(GateStatus.SHADOW) to "Logged (gate not decided yet)",
            mergeGate(GateStatus.UNGATED) to "No gate for this change",
        )
        for ((gate, title) in cases) {
            repository.checks.clear()
            checks.publish(gate, sole)
            assertEquals(title, api.published.last().second.title)
        }
        assertEquals("failure", api.published[3].second.conclusion)
        assertEquals(
            "No decided gate covers a merge into main in acme/api.",
            api.published.last().second.summary,
        )
        checks.publish(mergeGate(GateStatus.PENDING), listOf(request("lead", RequestStatus.CLOSED)))
        assertEquals("Waiting for a yes from -", api.published.last().second.title)
        val unsigned = mergeGate(GateStatus.APPROVED).copy(receiptId = null)
        repository.checks.clear()
        checks.publish(unsigned, sole)
        assertEquals("Gate \"Merge into main\". Signed receipt -.", api.published.last().second.summary)
    }

    @Test
    fun aCompletedCheckWhoseConclusionChangesIsANewRun() = runTest {
        installed()
        checks.publish(mergeGate(GateStatus.SHADOW), emptyList())
        checks.publish(mergeGate(GateStatus.SHADOW), emptyList())
        assertEquals(listOf(null, 100L), api.published.map { it.third }, "Same conclusion: the same run")
        checks.publish(mergeGate(GateStatus.REJECTED), emptyList())
        assertNull(api.published.last().third, "Another conclusion: a new run, GitHub keeps the last")
    }

    @Test
    fun nothingIsPublishedWithoutAPullRequestARepositoryOrAnInstallation() = runTest {
        assertNull(checks.publish(mergeGate(), emptyList()), "No installation")
        installed()
        assertNull(checks.publish(mergeGate(pullRequest = false), emptyList()))
        assertNull(checks.publish(mergeGate(repo = null), emptyList()))
        assertEquals(emptyList(), api.published)
        assertEquals("github-check", checks.name)
    }

    @Test
    fun theHeadOfAPullRequestIsReadFromGitHubAndOnlyWhileOpen() = runTest {
        val logger = RecordingLogger()
        val heads = GitHubPullRequestHeads(repository, api, logger)
        api.pulls["acme/api" to 6] = GitHubPull(6, SHA, "main", open = true, author = "dev")
        api.pulls["acme/api" to 7] = GitHubPull(7, SHA, "main", open = false, author = "dev")
        assertNull(heads.head(ORG, "acme/api", 6), "No installation: no head, the gate refuses an enforced merge")
        installed()
        assertEquals(GatePullRequest(6, SHA, "main"), heads.head(ORG, "acme/api", 6))
        assertNull(heads.head(ORG, "acme/api", 7), "Closed")
        assertNull(heads.head(ORG, "acme/api", 8), "Unknown")
        api.failure = gitHubDown()
        assertNull(heads.head(ORG, "acme/api", 6), "GitHub down: fail closed")
        assertEquals(listOf(LogLevel.WARN to "pull request head not read from github"), logger.lines)
        api.failure = CancellationException("stop")
        assertFailsWith<CancellationException> { heads.head(ORG, "acme/api", 6) }
    }
}
