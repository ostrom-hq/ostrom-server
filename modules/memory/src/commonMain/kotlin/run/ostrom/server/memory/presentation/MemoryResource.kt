// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory.presentation

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

/**
 * `POST /api/v1/memory` (`createMemory`) and the memory's other routes, as in ostrom-core's `api/openapi.yaml` (tag
 * `memory`).
 */
@Serializable
@Resource("/api/v1/memory")
class MemoryResource {
    /**
     * `GET /api/v1/memory/index` (`listMemory`).
     *
     * @property scope `team` or `personal`.
     * @property tags Comma-separated tags.
     * @property after The `next` of the previous page.
     * @property limit The page size, 1 to 50.
     */
    @Serializable
    @Resource("index")
    class Index(
        val parent: MemoryResource = MemoryResource(),
        val scope: String? = null,
        val tags: String? = null,
        val after: String? = null,
        val limit: String? = null,
    )

    /**
     * `GET /api/v1/memory/search` (`searchMemory`).
     *
     * @property q The words.
     * @property tags Comma-separated tags.
     * @property limit At most this many entries, 1 to 20.
     */
    @Serializable
    @Resource("search")
    class Search(
        val parent: MemoryResource = MemoryResource(),
        val q: String? = null,
        val tags: String? = null,
        val limit: String? = null,
    )

    /** `GET /api/v1/memory/proposals` (`listMemoryProposals`). */
    @Serializable
    @Resource("proposals")
    class Proposals(val parent: MemoryResource = MemoryResource()) {
        /**
         * `POST /api/v1/memory/proposals/{id}/accept` (`acceptMemoryProposal`).
         *
         * @property id The proposal's id.
         */
        @Serializable
        @Resource("{id}/accept")
        class Accept(val parent: Proposals = Proposals(), val id: String)

        /**
         * `POST /api/v1/memory/proposals/{id}/reject` (`rejectMemoryProposal`).
         *
         * @property id The proposal's id.
         */
        @Serializable
        @Resource("{id}/reject")
        class Reject(val parent: Proposals = Proposals(), val id: String)
    }

    /** `GET` and `PUT /api/v1/memory/curation` (`getMemoryCuration`, `setMemoryCuration`). */
    @Serializable
    @Resource("curation")
    class Curation(val parent: MemoryResource = MemoryResource())

    /** `GET /api/v1/memory/reviews` (`listMemoryReviews`). */
    @Serializable
    @Resource("reviews")
    class Reviews(val parent: MemoryResource = MemoryResource()) {
        /**
         * `POST /api/v1/memory/reviews/{id}/apply` (`applyMemoryReview`).
         *
         * @property id The review's id.
         */
        @Serializable
        @Resource("{id}/apply")
        class Apply(val parent: Reviews = Reviews(), val id: String)

        /**
         * `POST /api/v1/memory/reviews/{id}/dismiss` (`dismissMemoryReview`).
         *
         * @property id The review's id.
         */
        @Serializable
        @Resource("{id}/dismiss")
        class Dismiss(val parent: Reviews = Reviews(), val id: String)
    }

    /** `GET /api/v1/memory/superseded` (`listSupersededMemory`). */
    @Serializable
    @Resource("superseded")
    class Superseded(val parent: MemoryResource = MemoryResource())

    /**
     * `GET`, `PATCH` and `DELETE /api/v1/memory/{id}` (`readMemory`, `updateMemory`, `forgetMemory`).
     *
     * @property id The entry's id.
     */
    @Serializable
    @Resource("{id}")
    class Entry(val parent: MemoryResource = MemoryResource(), val id: String) {
        /** `POST /api/v1/memory/{id}/restore` (`restoreMemory`). */
        @Serializable
        @Resource("restore")
        class Restore(val parent: Entry)
    }
}
