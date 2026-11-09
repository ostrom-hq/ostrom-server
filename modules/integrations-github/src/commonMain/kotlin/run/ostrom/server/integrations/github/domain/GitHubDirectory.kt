// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.integrations.github.domain

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A repository the organization's installation of the App sees, as last read from GitHub.
 *
 * @property name Its name, without the owner (the installation is on one account).
 * @property description Its description.
 * @property language Its main language.
 * @property defaultBranch Its default branch.
 * @property archived Whether it is archived.
 * @property pushedAt Its last push.
 * @property owners The slugs of the GitHub teams granted it, sorted.
 */
data class GitHubRepo(
    val name: String,
    val description: String?,
    val language: String?,
    val defaultBranch: String,
    val archived: Boolean,
    val pushedAt: Instant?,
    val owners: List<String> = emptyList(),
)

/**
 * A team of the GitHub organization the App is installed on.
 *
 * @property slug Its slug.
 * @property name Its name.
 * @property members The logins of its members (GitHub's first 100).
 * @property repos The names of its repositories (GitHub's first 100).
 */
data class GitHubTeam(val slug: String, val name: String, val members: List<String>, val repos: List<String>)

/**
 * What one read of GitHub gives: the repositories and the teams.
 *
 * @property repos The repositories, each with the teams that own it.
 * @property teams The teams.
 */
data class GitHubDirectory(val repos: List<GitHubRepo>, val teams: List<GitHubTeam>)

/**
 * When the organization's directory was last read and last tried.
 *
 * @property syncedAt The last successful read, or null when none succeeded yet.
 * @property checkedAt The last time a read was started.
 */
data class DirectoryState(val syncedAt: Instant?, val checkedAt: Instant)

/**
 * One page of a list sorted by name.
 *
 * @property items The items.
 * @property next The cursor of the next page (the last name of this one), or null on the last page.
 */
data class NamePage<T>(val items: List<T>, val next: String?)

/** GitHub could not be read and nothing is stored yet: the caller tries again. */
class GitHubUnavailableException :
    OstromException(ErrorKind.UNAVAILABLE, "GitHub did not answer. Try again in a moment.")

/** A page size or a cursor out of range. */
class InvalidPageException(message: String) : OstromException(ErrorKind.INVALID_REQUEST, message)

/** What GitHub tells about the installation's account, with the installation's own token (read-only). */
interface GitHubDirectoryApi {
    /**
     * Lists the repositories the installation sees.
     *
     * @param installationId The installation.
     * @return The repositories, without owners.
     * @throws GitHubCallException When GitHub fails.
     */
    suspend fun repositories(installationId: Long): List<GitHubRepo>

    /**
     * Lists the teams of the account the installation is on.
     *
     * @param installationId The installation.
     * @param account The account, an organization or a user.
     * @return The teams; none for a user's account or an App not allowed to read the members.
     * @throws GitHubCallException When GitHub fails.
     */
    suspend fun teams(installationId: Long, account: String): List<GitHubTeam>
}

/** The organization's repositories and teams as last read from GitHub, and when. Every call is one organization's. */
interface GitHubDirectoryRepository {
    /**
     * @param orgId The organization.
     * @return When its directory was read and tried, or null when never.
     */
    suspend fun state(orgId: Uuid): DirectoryState?

    /**
     * Takes the next background read: only one caller, across every server, wins it per period.
     *
     * @param orgId The organization.
     * @param now The current time, recorded as the try.
     * @param staleBefore A try older than this is due.
     * @return True for the caller that won it.
     */
    suspend fun claim(orgId: Uuid, now: Instant, staleBefore: Instant): Boolean

    /**
     * Replaces the stored directory with a read of GitHub, in one transaction.
     *
     * @param orgId The organization.
     * @param directory The read.
     * @param at When it was read.
     */
    suspend fun replace(orgId: Uuid, directory: GitHubDirectory, at: Instant)

    /**
     * Forgets when the directory was read, so that the next list reads GitHub again (a new installation).
     *
     * @param orgId The organization.
     */
    suspend fun forget(orgId: Uuid)

    /**
     * @param orgId The organization.
     * @param after The last name of the previous page, or null.
     * @param limit The most rows.
     * @return The repositories, by name.
     */
    suspend fun repos(orgId: Uuid, after: String?, limit: Int): List<GitHubRepo>

    /**
     * @param orgId The organization.
     * @param after The last slug of the previous page, or null.
     * @param limit The most rows.
     * @return The teams, by slug.
     */
    suspend fun teams(orgId: Uuid, after: String?, limit: Int): List<GitHubTeam>

    /**
     * @param orgId The organization.
     * @param name A repository's name.
     * @return True when the organization's directory holds it.
     */
    suspend fun hasRepo(orgId: Uuid, name: String): Boolean

    /**
     * @param orgId The organization.
     * @param slug A team's slug.
     * @return True when the organization's directory holds it.
     */
    suspend fun hasTeam(orgId: Uuid, slug: String): Boolean
}
