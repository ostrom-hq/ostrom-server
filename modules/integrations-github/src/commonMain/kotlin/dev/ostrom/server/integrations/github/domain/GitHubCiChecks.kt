// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.github.domain

import dev.ostrom.server.gates.domain.models.CiCheck
import dev.ostrom.server.gates.domain.services.CiChecks
import dev.ostrom.server.kernel.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The `ci` attestation's proof (decision of 30/10 15:14): GitHub's own record of a check run on the commit, read with
 * the organization's installation token, never what a workstation reports.
 *
 * Only a completed run counts, on exactly that commit and under exactly that name, created by another App than
 * Ostrom's: the `ostrom-gate` check says what a person decided, not what a CI ran. The installation reaches only the
 * repositories the organization granted it, so another organization's repository reads nothing. GitHub failing gives
 * no proof (logged), never an error: the check node then stays the workstation's declaration.
 *
 * @param repository The installations.
 * @param api GitHub.
 * @param ownApp The slug of Ostrom's App, null when none is configured.
 * @param logger Logs GitHub failing.
 */
class GitHubCiChecks(
    private val repository: GitHubRepository,
    private val api: GitHubCiApi,
    private val ownApp: suspend () -> String?,
    private val logger: Logger,
) : CiChecks {
    override suspend fun latest(orgId: Uuid, repo: String, sha: String, name: String): CiCheck? {
        // Both go into GitHub's path: anything else than a repository's full name and a commit's hex is not asked.
        val asked = REPO.matches(repo) && SHA.matches(sha) && name.isNotBlank()
        val installation = if (asked) repository.installation(orgId) else null
        if (installation == null) return null
        val runs = try {
            api.checkRuns(installation.installationId, repo, sha, name)
        } catch (error: CancellationException) {
            throw error
        } catch (error: GitHubCallException) {
            logger.warn("check runs not read from github", mapOf("repo" to repo), error)
            return null
        }
        val own = ownApp()
        val run = runs.filter { it.status == COMPLETED && it.headSha == sha && it.name == name && it.app != own }
            .maxByOrNull { it.completedAt ?: Instant.DISTANT_PAST } ?: return null
        val conclusion = run.conclusion ?: return null
        return CiCheck(run.id, run.name, run.headSha, conclusion, run.app, run.url, run.completedAt)
    }
}

private const val COMPLETED = "completed"
private val REPO = Regex("^[A-Za-z0-9][A-Za-z0-9-]{0,38}/\\.?[A-Za-z0-9_-][A-Za-z0-9._-]{0,98}$")
private val SHA = Regex("^[0-9a-f]{40}([0-9a-f]{24})?$")
