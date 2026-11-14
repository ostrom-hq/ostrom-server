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

    /**
     * `GET`, `PATCH` and `DELETE /api/v1/memory/{id}` (`readMemory`, `updateMemory`, `forgetMemory`).
     *
     * @property id The entry's id.
     */
    @Serializable
    @Resource("{id}")
    class Entry(val parent: MemoryResource = MemoryResource(), val id: String)
}
