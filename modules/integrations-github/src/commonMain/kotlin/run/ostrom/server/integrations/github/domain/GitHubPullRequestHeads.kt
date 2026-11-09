// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.integrations.github.domain

import run.ostrom.server.gates.domain.models.GatePullRequest
import run.ostrom.server.gates.domain.services.PullRequestHeads
import run.ostrom.server.kernel.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlin.uuid.Uuid

/**
 * The head of a pull request read from GitHub with the organization's installation (SECURITY-AUDIT S2-05, S2-06):
 * the yes of a merge is bound to that commit and that base, never to what the hook or the CI says. A closed pull
 * request has no head to approve; GitHub failing or not answering gives none either, and the gates module refuses
 * an enforced merge it cannot bind (fail-closed).
 *
 * @param repository The installations.
 * @param api GitHub.
 * @param logger Logs GitHub failing.
 */
class GitHubPullRequestHeads(
    private val repository: GitHubRepository,
    private val api: GitHubApi,
    private val logger: Logger,
) : PullRequestHeads {
    override suspend fun head(orgId: Uuid, repo: String, number: Int): GatePullRequest? {
        val installation = repository.installation(orgId) ?: return null
        val pull = try {
            api.pullRequest(installation.installationId, repo, number)
        } catch (error: CancellationException) {
            throw error
        } catch (error: GitHubCallException) {
            logger.warn("pull request head not read from github", mapOf("repo" to repo, "pr" to number), error)
            null
        }
        return pull?.takeIf { it.open }?.let { GatePullRequest(it.number, it.headSha, it.base) }
    }
}
