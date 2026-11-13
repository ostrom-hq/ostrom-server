// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.github.domain

import run.ostrom.server.kernel.errors.Pages
import kotlin.uuid.Uuid

/** Lists the repositories the organization's installation of the App sees (`GET /api/v1/repos`). */
interface ListGitHubReposUseCase {
    /**
     * @param orgId The organization.
     * @param after The `next` of the previous page, or null.
     * @param limit The page size, 1 to 100 (100 when null).
     * @return The page, by name; empty without an installation.
     * @throws InvalidPageException For a size out of range or a cursor too long.
     * @throws GitHubUnavailableException When nothing is stored yet and GitHub does not answer.
     */
    suspend operator fun invoke(orgId: Uuid, after: String?, limit: Int?): NamePage<GitHubRepo>
}

/** Lists the teams of the GitHub organization the App is installed on (`GET /api/v1/orgs/{org}/teams`). */
interface ListGitHubTeamsUseCase {
    /**
     * @param orgId The organization.
     * @param after The `next` of the previous page, or null.
     * @param limit The page size, 1 to 100 (100 when null).
     * @return The page, by slug; empty without an installation.
     * @throws InvalidPageException For a size out of range or a cursor too long.
     * @throws GitHubUnavailableException When nothing is stored yet and GitHub does not answer.
     */
    suspend operator fun invoke(orgId: Uuid, after: String?, limit: Int?): NamePage<GitHubTeam>
}

/**
 * [ListGitHubReposUseCase].
 *
 * @param sync Keeps the stored directory fresh.
 * @param directory The stored directory.
 */
class ListGitHubReposUseCaseImpl(
    private val sync: GitHubDirectorySync,
    private val directory: GitHubDirectoryRepository,
) : ListGitHubReposUseCase {
    override suspend fun invoke(orgId: Uuid, after: String?, limit: Int?): NamePage<GitHubRepo> {
        val size = pageSize(after, limit)
        if (!sync.ensure(orgId)) return nothing(after)
        val read = directory.repos(orgId, after, size + 1)
        val rows = Pages.checked("after", after, read) { directory.hasRepo(orgId, it) }
        return page(rows, size) { it.name }
    }
}

/**
 * [ListGitHubTeamsUseCase].
 *
 * @param sync Keeps the stored directory fresh.
 * @param directory The stored directory.
 */
class ListGitHubTeamsUseCaseImpl(
    private val sync: GitHubDirectorySync,
    private val directory: GitHubDirectoryRepository,
) : ListGitHubTeamsUseCase {
    override suspend fun invoke(orgId: Uuid, after: String?, limit: Int?): NamePage<GitHubTeam> {
        val size = pageSize(after, limit)
        if (!sync.ensure(orgId)) return nothing(after)
        val read = directory.teams(orgId, after, size + 1)
        val rows = Pages.checked("after", after, read) { directory.hasTeam(orgId, it) }
        return page(rows, size) { it.slug }
    }
}

// GitHub caps a repository name at 100 characters and a team slug below that.
private const val MAX_CURSOR = 200
private const val MAX_PAGE = 100

private fun pageSize(after: String?, limit: Int?): Int {
    val size = limit ?: MAX_PAGE
    if (size !in 1..MAX_PAGE) throw InvalidPageException("limit is between 1 and $MAX_PAGE.")
    if (after != null && after.length > MAX_CURSOR) throw InvalidPageException("This cursor is not one of ours.")
    return size
}

// Without an installation the list is empty and no page ever gave a cursor: one is refused (a 400), not ignored.
private fun <T> nothing(after: String?): NamePage<T> {
    if (after != null) Pages.unknownCursor("after")
    return NamePage(emptyList(), null)
}

// Read one row more than the page: when it exists, the page has a next one, named by its last row.
private fun <T> page(rows: List<T>, size: Int, key: (T) -> String): NamePage<T> {
    val items = rows.take(size)
    return NamePage(items, if (rows.size > size) key(items.last()) else null)
}
