// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules.domain.models

/**
 * A repository the analysis may read, as the organization's GitHub App installation lists it.
 *
 * @property owner The GitHub account that owns it.
 * @property name Its short name, as rules scope it (`api`, `api/db/migration`).
 * @property defaultBranch The branch the analysis reads.
 * @property archived True when GitHub archived it; an archived repository is not analysed.
 * @property description Its description, if any.
 * @property pushedAt Its last push (ISO 8601), if known.
 * @property webUrl Its page (`https://github.com/acme/api`, or the GitHub Enterprise Server one), for the rules' links.
 */
data class RepositoryRef(
    val owner: String,
    val name: String,
    val defaultBranch: String,
    val archived: Boolean,
    val description: String?,
    val pushedAt: String?,
    val webUrl: String,
)

/** What the organization's GitHub connection lists. */
sealed interface RepositoryListing {
    /**
     * Connected: the repositories its installation is granted.
     *
     * @property repos The repositories.
     */
    data class Connected(val repos: List<RepositoryRef>) : RepositoryListing

    /**
     * Not connected: nothing can be read.
     *
     * @property reason One readable sentence saying what to do, never a secret.
     */
    data class NotConnected(val reason: String) : RepositoryListing
}

/**
 * The files of a repository's default branch, by path, without their contents.
 *
 * @property paths The paths of its files (blobs), relative to the root.
 * @property truncated True when GitHub cut the listing (very large trees): the analysis says so in its reasons.
 */
data class RepositoryTree(val paths: List<String>, val truncated: Boolean)

/**
 * How GitHub protects a branch, from its classic protection and its rulesets.
 *
 * @property protected True when anything protects it.
 * @property requiredApprovals The approvals a pull request needs to merge; 0 when none is required.
 * @property codeOwnerReviews True when the code owners must approve.
 * @property requiredChecks The status checks that must pass, by name.
 * @property blocksForcePush True when a force-push is refused.
 */
data class BranchProtection(
    val protected: Boolean,
    val requiredApprovals: Int,
    val codeOwnerReviews: Boolean,
    val requiredChecks: List<String>,
    val blocksForcePush: Boolean,
)

/**
 * What the analysis read of one repository: never a clone, only the listing, a few small files and a setting.
 *
 * @property repo The repository.
 * @property tree Its files.
 * @property files The contents it read, by path (`CODEOWNERS`, the root `package.json`).
 * @property protection Its default branch's protection, or null when it could not be read.
 */
data class RepositorySnapshot(
    val repo: RepositoryRef,
    val tree: RepositoryTree,
    val files: Map<String, String>,
    val protection: BranchProtection?,
)

/**
 * A read of GitHub that failed (network, permission, rate limit): its message is one readable sentence, never a
 * token or a raw response body.
 *
 * @property message The sentence.
 */
class RepositoryReadException(override val message: String) : Exception(message)
