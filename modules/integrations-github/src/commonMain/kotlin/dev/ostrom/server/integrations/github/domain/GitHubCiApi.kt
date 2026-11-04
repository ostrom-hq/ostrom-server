// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.github.domain

import kotlin.time.Instant

/**
 * A check run as GitHub reports it on a commit.
 *
 * @property id GitHub's id.
 * @property name Its name (the CI job or check).
 * @property headSha The commit it ran on.
 * @property status `queued`, `in_progress` or `completed`.
 * @property conclusion How it completed, null before.
 * @property app The slug of the GitHub App that created it (`github-actions` for GitHub Actions).
 * @property url Its page on GitHub.
 * @property completedAt When it completed, null before.
 */
data class GitHubCiRun(
    val id: Long,
    val name: String,
    val headSha: String,
    val status: String,
    val conclusion: String?,
    val app: String,
    val url: String?,
    val completedAt: Instant?,
)

/** GitHub's check runs of a commit, read as the organization's installation of the App. */
interface GitHubCiApi {
    /**
     * Lists the latest run of [name] on [sha], per App (`GET /repos/{repo}/commits/{sha}/check-runs`).
     *
     * @param installationId The installation.
     * @param repo `owner/name`.
     * @param sha The commit.
     * @param name The check run's name.
     * @return The runs; none when the installation does not reach the repository or GitHub does not know the commit.
     * @throws GitHubCallException When GitHub fails.
     */
    suspend fun checkRuns(installationId: Long, repo: String, sha: String, name: String): List<GitHubCiRun>
}
