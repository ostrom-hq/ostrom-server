// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.github.domain

import run.ostrom.server.gates.domain.models.CiCheck
import run.ostrom.server.gates.domain.services.CiChecks
import run.ostrom.server.kernel.logging.Logger
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
        val asked = isRepositoryName(repo) && SHA.matches(sha) && name.isNotBlank()
        val installation = if (asked) repository.installation(orgId) else null
        val runs = installation?.let { read(it.installationId, repo, sha, name) }.orEmpty()
        val own = ownApp()
        val run = runs.filter { it.status == COMPLETED && it.headSha == sha && it.name == name && it.app != own }
            .maxByOrNull { it.completedAt ?: Instant.DISTANT_PAST }
        val conclusion = run?.conclusion
        return if (run == null || conclusion == null) {
            null
        } else {
            CiCheck(run.id, run.name, run.headSha, conclusion, run.app, run.url, run.completedAt)
        }
    }

    private suspend fun read(installationId: Long, repo: String, sha: String, name: String): List<GitHubCiRun> = try {
        api.checkRuns(installationId, repo, sha, name)
    } catch (error: CancellationException) {
        throw error
    } catch (error: GitHubCallException) {
        logger.warn("check runs not read from github", mapOf("repo" to repo), error)
        emptyList()
    }
}

private const val COMPLETED = "completed"
private val SHA = Regex("^[0-9a-f]{40}([0-9a-f]{24})?$")
