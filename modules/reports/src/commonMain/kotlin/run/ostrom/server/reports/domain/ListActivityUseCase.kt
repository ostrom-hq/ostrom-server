// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.reports.domain

/** Reads the activity feed of the viewer's organization. */
interface ListActivityUseCase {
    /**
     * Reads a page.
     *
     * @param viewer Who reads; any member of the organization sees its feed.
     * @param before Where the page starts, null for the newest.
     * @param repo Only what concerns this repository, or null.
     * @return The page.
     */
    suspend operator fun invoke(viewer: Viewer, before: ActivityCursor?, repo: String?): ActivityPage
}

/**
 * [ListActivityUseCase] on the repository.
 *
 * @param repository The reads.
 */
class ListActivityUseCaseImpl(private val repository: ReportsRepository) : ListActivityUseCase {
    override suspend fun invoke(viewer: Viewer, before: ActivityCursor?, repo: String?): ActivityPage {
        // One more than the page tells whether another page follows, without an empty last page.
        val items = repository.activity(viewer.orgId, before, repo, PAGE + 1)
        val page = items.take(PAGE)
        return ActivityPage(page, if (items.size > PAGE) page.last().cursor else null)
    }

    private companion object {
        const val PAGE = 50
    }
}
