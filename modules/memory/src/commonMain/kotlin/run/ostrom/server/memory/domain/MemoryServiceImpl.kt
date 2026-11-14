// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory.domain

import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * [MemoryService]: validates, decides the origin and who may write the organization's memory, and audits every write
 * with the entry's id, scope and origin, never its text.
 *
 * @param repository The memory's storage.
 * @param audit The organization's audit log.
 * @param clock The time an expiry is checked against.
 */
class MemoryServiceImpl(
    private val repository: MemoryRepository,
    private val audit: MemoryAudit,
    private val clock: Clock,
) : MemoryService {
    override suspend fun index(
        caller: MemoryCaller,
        scope: MemoryScope?,
        tags: List<String>,
        after: MemoryCursor?,
        limit: Int,
    ): MemoryPage = repository.index(caller, scope, filter(tags), after, limit.coerceIn(1, MemoryRules.PAGE_LIMIT))

    override suspend fun search(
        caller: MemoryCaller,
        query: String,
        tags: List<String>,
        limit: Int,
    ): List<MemoryIndexEntry> {
        val terms = MemoryRules.terms(query)
        return repository.search(caller, terms, filter(tags), limit.coerceIn(1, MemoryRules.SEARCH_LIMIT))
    }

    override suspend fun read(caller: MemoryCaller, id: Uuid): MemoryEntry = repository.read(caller, id)

    override suspend fun create(caller: MemoryCaller, entry: NewMemory, sessionId: String?): MemoryEntry {
        val now = clock.now()
        MemoryRules.validate(entry.title, entry.summary, entry.content, entry.tags, entry.refs, entry.expiresAt, now)
        val agent = caller.agent || !caller.person
        if (entry.scope == MemoryScope.TEAM && agent) {
            throw MemoryTeamForbiddenException(
                "An agent cannot write to the team memory here. Write a personal entry, or ask a member to add it.",
            )
        }
        val origin = if (agent) MemoryOrigin.AGENT else MemoryOrigin.HUMAN
        val created = repository.create(caller, entry.copy(tags = entry.tags.sorted()), origin, sessionId)
        audit.record(caller, "memory.created", created.id, "scope" to created.scope.wire, "origin" to origin.wire)
        return created
    }

    override suspend fun update(caller: MemoryCaller, id: Uuid, update: MemoryUpdate): MemoryEntry {
        MemoryRules.validate(
            update.title,
            update.summary,
            update.content,
            update.tags,
            update.refs,
            update.expiresAt,
            clock.now(),
        )
        val updated = repository.update(caller, id, update.copy(tags = update.tags.sorted())) { entry ->
            requireEditable(caller, entry)
            if (entry.version != update.expectedVersion) throw MemoryConflictException()
        }
        audit.record(caller, "memory.updated", id)
        return updated
    }

    override suspend fun delete(caller: MemoryCaller, id: Uuid) {
        repository.delete(caller, id) { entry -> requireEditable(caller, entry) }
        audit.record(caller, "memory.forgotten", id)
    }

    /** A filter's tags, checked and without repeats. */
    private fun filter(tags: List<String>): List<String> {
        if (tags.size > MemoryRules.TAGS_LIMIT) {
            throw InvalidMemoryException("Filter on at most ${MemoryRules.TAGS_LIMIT} tags.")
        }
        tags.forEach(MemoryRules::validateTag)
        return tags.distinct()
    }

    /**
     * A personal entry is its owner's (the only one who sees it). An organization's entry is changed by its author or
     * an administrator, as a person; an agent never changes what the organization reads.
     */
    private fun requireEditable(caller: MemoryCaller, entry: MemoryEntry) {
        if (entry.scope == MemoryScope.PERSONAL) return
        if (!caller.person || caller.agent || (entry.authorId != caller.memberId && !caller.admin)) {
            throw MemoryTeamForbiddenException("Only the author of a team entry or an administrator can change it.")
        }
    }
}
