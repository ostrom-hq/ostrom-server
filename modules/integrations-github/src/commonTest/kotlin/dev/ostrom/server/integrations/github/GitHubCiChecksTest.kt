// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.github

import dev.ostrom.server.gates.domain.models.CiCheck
import dev.ostrom.server.integrations.github.domain.GitHubCallException
import dev.ostrom.server.integrations.github.domain.GitHubCiApi
import dev.ostrom.server.integrations.github.domain.GitHubCiChecks
import dev.ostrom.server.integrations.github.domain.GitHubCiRun
import dev.ostrom.server.integrations.github.domain.GitHubInstallation
import dev.ostrom.server.kernel.logging.LogLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class GitHubCiChecksTest {
    private val repository = MemoryGitHubRepository().apply { installations += GitHubInstallation(ORG, 42, "acme") }
    private val logger = RecordingLogger()
    private val api = FakeGitHubCiApi()

    private fun checks(own: String? = "ostrom") = GitHubCiChecks(repository, api, { own }, logger)

    // GitHub Actions' run of `test` on the commit, completed with success at NOW + [completed] minutes.
    private fun run(id: Long, completed: Int? = 0) = GitHubCiRun(
        id = id,
        name = "test",
        headSha = SHA,
        status = "completed",
        conclusion = "success",
        app = "github-actions",
        url = "https://github.test/runs/$id",
        completedAt = completed?.let { NOW + it.minutes },
    )

    @Test
    fun theLatestCompletedRunOfAnotherAppOnThatCommitAndNameIsTheProof() = runTest {
        api.runs = listOf(
            run(1, completed = 1),
            run(2, completed = 5).copy(conclusion = "failure"),
            run(3, completed = 9).copy(app = "ostrom"),
            run(4, completed = null).copy(status = "in_progress", conclusion = null),
            run(5, completed = 9).copy(headSha = "f".repeat(40)),
            run(6, completed = 9).copy(name = "lint"),
            run(7, completed = null),
        )
        val proof = checks().latest(ORG, "acme/api", SHA, "test")
        assertEquals(
            CiCheck(2, "test", SHA, "failure", "github-actions", "https://github.test/runs/2", NOW + 5.minutes),
            proof,
        )
        assertEquals(listOf(Asked(42, "acme/api", SHA, "test")), api.asked)
    }

    @Test
    fun withoutOstromsAppEveryAppCounts() = runTest {
        api.runs = listOf(run(3).copy(app = "ostrom"))
        assertEquals(3L, checks(own = null).latest(ORG, "acme/api", SHA, "test")?.id)
    }

    @Test
    fun aRunWithoutItsDateCountsOnlyWhenNoneHasOne() = runTest {
        api.runs = listOf(run(7, completed = null))
        assertEquals(7L, checks().latest(ORG, "acme/api", SHA, "test")?.id)
        api.runs = listOf(run(7, completed = null), run(8, completed = -60), run(9, completed = null))
        assertEquals(8L, checks().latest(ORG, "acme/api", SHA, "test")?.id, "Any date is later than none")
    }

    @Test
    fun noMatchingRunOrNoConclusionIsNoProof() = runTest {
        api.runs = listOf(run(3).copy(app = "ostrom"), run(4).copy(status = "queued", conclusion = null))
        assertNull(checks().latest(ORG, "acme/api", SHA, "test"))
        api.runs = listOf(run(8).copy(conclusion = null))
        assertNull(checks().latest(ORG, "acme/api", SHA, "test"), "Completed without a conclusion proves nothing")
    }

    @Test
    fun anOrganizationWithoutAnInstallationAsksGitHubNothing() = runTest {
        assertNull(checks().latest(OTHER_ORG, "acme/api", SHA, "test"))
        assertTrue(api.asked.isEmpty())
    }

    @Test
    fun onlyARepositorysFullNameACommitsHexAndANameAreAsked() = runTest {
        val refused = listOf(
            Triple("acme", SHA, "test"),
            Triple("acme/..", SHA, "test"),
            Triple("acme/api/../../x", SHA, "test"),
            Triple("acme/api", "main", "test"),
            Triple("acme/api", SHA.uppercase(), "test"),
            Triple("acme/api", SHA, " "),
        )
        for ((repo, sha, name) in refused) assertNull(checks().latest(ORG, repo, sha, name), "$repo $sha '$name'")
        assertTrue(api.asked.isEmpty())
        api.runs = listOf(run(1))
        assertEquals(1L, checks().latest(ORG, "acme/.github", SHA, "test")?.id, "A dotted repository name is one")
        val sha256 = "a".repeat(64)
        api.runs = listOf(run(2).copy(headSha = sha256))
        assertEquals(2L, checks().latest(ORG, "acme/api", sha256, "test")?.id, "A SHA-256 repository's commit")
    }

    @Test
    fun gitHubFailingIsNoProofAndIsLogged() = runTest {
        api.failure = GitHubCallException("GitHub refused the check runs (500).")
        assertNull(checks().latest(ORG, "acme/api", SHA, "test"))
        assertEquals(listOf(LogLevel.WARN to "check runs not read from github"), logger.lines)
    }

    @Test
    fun aCancellationIsNeverSwallowed() = runTest {
        api.failure = CancellationException("cancelled")
        assertFailsWith<CancellationException> { checks().latest(ORG, "acme/api", SHA, "test") }
    }
}

data class Asked(val installationId: Long, val repo: String, val sha: String, val name: String)

/** GitHub's check runs of a commit, as set by the test; [failure] is thrown instead when set. */
class FakeGitHubCiApi : GitHubCiApi {
    var runs = emptyList<GitHubCiRun>()
    var failure: Throwable? = null
    val asked = mutableListOf<Asked>()

    override suspend fun checkRuns(installationId: Long, repo: String, sha: String, name: String): List<GitHubCiRun> {
        asked += Asked(installationId, repo, sha, name)
        failure?.let { throw it }
        return runs
    }
}
