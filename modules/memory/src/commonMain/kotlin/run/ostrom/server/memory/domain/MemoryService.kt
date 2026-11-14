// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory.domain

import kotlin.uuid.Uuid

/**
 * What a member, their agent or their key does with the memory (TeamCode's `MemoryService`). Who the caller is was
 * resolved before ([MemoryCaller]); the rules that depend on stored state run inside the repository's transaction.
 */
interface MemoryService {
    /**
     * A page of the index.
     *
     * @throws InvalidMemoryException For a tag that is not one.
     */
    suspend fun index(
        caller: MemoryCaller,
        scope: MemoryScope? = null,
        tags: List<String> = emptyList(),
        after: MemoryCursor? = null,
        limit: Int = MemoryRules.PAGE_LIMIT,
    ): MemoryPage

    /**
     * The entries whose title, summary or a tag contains every word of [query].
     *
     * @throws InvalidMemoryException Without a usable word, or for a tag that is not one.
     */
    suspend fun search(
        caller: MemoryCaller,
        query: String,
        tags: List<String> = emptyList(),
        limit: Int = MemoryRules.SEARCH_LIMIT,
    ): List<MemoryIndexEntry>

    /** One entry with its content, counted as a read. */
    suspend fun read(caller: MemoryCaller, id: Uuid): MemoryEntry

    /**
     * Remembers something. A personal entry is written directly by any caller. An organization's entry is written
     * directly by a person only; an agent or a key is refused ([MemoryTeamForbiddenException]) until proposals land.
     *
     * @param sessionId The session an agent writes from, kept as provenance.
     */
    suspend fun create(caller: MemoryCaller, entry: NewMemory, sessionId: String? = null): MemoryEntry

    /**
     * Replaces an entry: a personal entry by its owner, an organization's by its author or an administrator, as a
     * person.
     */
    suspend fun update(caller: MemoryCaller, id: Uuid, update: MemoryUpdate): MemoryEntry

    /** Forgets an entry, with the rights of [update]. */
    suspend fun delete(caller: MemoryCaller, id: Uuid)
}
