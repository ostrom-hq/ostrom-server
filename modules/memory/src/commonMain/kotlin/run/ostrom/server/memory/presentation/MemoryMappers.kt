// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory.presentation

import run.ostrom.core.api.models.CreateMemoryRequest
import run.ostrom.core.api.models.MemoryIndexPage
import run.ostrom.core.api.models.UpdateMemoryRequest
import run.ostrom.server.memory.domain.InvalidMemoryException
import run.ostrom.server.memory.domain.MemoryCursor
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

/** A comma-separated tag filter. */
internal fun tagsOf(value: String?): List<String> =
    value?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

/** A limit from the query: a number, or the default. */
internal fun limitOf(value: String?, default: Int): Int =
    value?.let { it.toIntOrNull() ?: throw InvalidMemoryException("Use a number as the limit.") } ?: default

/** A scope from the query; both when absent. */
internal fun scopeOf(value: String?): MemoryScope? = value?.let(MemoryScope::parse)

/** A cursor as [MemoryPage.next] wrote it (`<updatedAt>_<id>`). */
internal fun cursorOf(value: String?): MemoryCursor? = value?.let {
    val at = it.substringBefore('_', "").let(Instant::parseOrNull)
    val id = Uuid.parseOrNull(it.substringAfter('_', ""))
    if (at == null || id == null) throw InvalidMemoryException("Use the cursor a previous page returned.")
    MemoryCursor(at, id)
}

internal fun MemoryCursor.wire(): String = "${updatedAt}_$id"

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
