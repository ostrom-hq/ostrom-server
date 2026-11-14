// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory.presentation

import run.ostrom.core.api.models.CreateMemoryRequest
import run.ostrom.core.api.models.MemoryIndexPage
import run.ostrom.core.api.models.UpdateMemoryRequest
import run.ostrom.server.memory.domain.InvalidMemoryException
import run.ostrom.server.memory.domain.MemoryEntry
import run.ostrom.server.memory.domain.MemoryIndexEntry
import run.ostrom.server.memory.domain.MemoryNotFoundException
import run.ostrom.server.memory.domain.MemoryPage
import run.ostrom.server.memory.domain.MemoryScope
import run.ostrom.server.memory.domain.MemoryUpdate
import run.ostrom.server.memory.domain.NewMemory
import kotlin.time.Instant
import kotlin.uuid.Uuid
import run.ostrom.core.api.models.MemoryEntry as MemoryEntryDto
import run.ostrom.core.api.models.MemoryIndexEntry as MemoryIndexEntryDto

/** An entry's id from its path; anything else is an entry nobody has (404). */
internal fun entryId(value: String): Uuid = Uuid.parseOrNull(value) ?: throw MemoryNotFoundException()

/** An ISO time from a request. */
private fun instantOf(value: String?): Instant? = value?.let {
    Instant.parseOrNull(it) ?: throw InvalidMemoryException("Use an ISO-8601 time for the expiry.")
}

internal fun CreateMemoryRequest.toDomain(): NewMemory =
    NewMemory(MemoryScope.parse(scope), title, summary, content, tags, refs, instantOf(expiresAt))

internal fun UpdateMemoryRequest.toDomain(): MemoryUpdate =
    MemoryUpdate(expectedVersion, title, summary, content, tags, refs, instantOf(expiresAt))

internal fun MemoryIndexEntry.toDto(): MemoryIndexEntryDto =
    MemoryIndexEntryDto(id.toString(), scope.wire, title, summary, tags, updatedAt.toString())

internal fun MemoryPage.toDto(): MemoryIndexPage = MemoryIndexPage(entries.map { it.toDto() }, next?.wire())

internal fun MemoryEntry.toDto(): MemoryEntryDto = MemoryEntryDto(
    id = id.toString(),
    scope = scope.wire,
    title = title,
    summary = summary,
    content = content,
    tags = tags,
    refs = refs,
    status = status.wire,
    origin = origin.wire,
    authorId = authorId,
    sessionId = sessionId,
    supersedesId = supersedesId?.toString(),
    expiresAt = expiresAt?.toString(),
    version = version,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
    lastReadAt = lastReadAt?.toString(),
    readCount = readCount,
)
