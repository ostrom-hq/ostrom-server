// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory.domain

import kotlin.uuid.Uuid

/**
 * The memory's storage. Every query takes the caller and keeps a personal entry invisible to anyone but its owner, in
 * the query itself, never filtered after reading; the rules that depend on stored state (visibility, ownership,
 * limits, versions) are checked inside the write's transaction.
 */
interface MemoryRepository {
    /**
     * The caller's active, unexpired entries (the organization's and their own), newest first, of [scope] and with any
     * of [tags].
     */
    suspend fun index(
        caller: MemoryCaller,
        scope: MemoryScope?,
        tags: List<String>,
        after: MemoryCursor?,
        limit: Int,
    ): MemoryPage

    /** Entries whose title, summary or a tag contains every term; same visibility as [index]. */
    suspend fun search(
        caller: MemoryCaller,
        terms: List<String>,
        tags: List<String>,
        limit: Int,
    ): List<MemoryIndexEntry>

    /**
     * One visible entry, counted as a read.
     *
     * @throws MemoryNotFoundException When there is none the caller may read.
     */
    suspend fun read(caller: MemoryCaller, id: Uuid): MemoryEntry

    /**
     * Stores an entry within the memory's limits.
     *
     * @param sessionId The session an agent wrote it from.
     * @throws MemoryLimitException When the memory is full.
     */
    suspend fun create(
        caller: MemoryCaller,
        entry: NewMemory,
        origin: MemoryOrigin,
        sessionId: String?,
        status: MemoryStatus = MemoryStatus.ACTIVE,
    ): MemoryEntry

    /**
     * Replaces a visible entry, locked while [check] decides whether the caller may (it throws otherwise).
     *
     * @throws MemoryNotFoundException When the caller may not see it.
     */
    suspend fun update(caller: MemoryCaller, id: Uuid, update: MemoryUpdate, check: (MemoryEntry) -> Unit): MemoryEntry

    /**
     * Deletes a visible entry, locked while [check] decides whether the caller may (it throws otherwise).
     *
     * @throws MemoryNotFoundException When the caller may not see it.
     */
    suspend fun delete(caller: MemoryCaller, id: Uuid, check: (MemoryEntry) -> Unit)

    /** Erases every entry of a deleted organization. */
    suspend fun purge(orgId: Uuid)
}
