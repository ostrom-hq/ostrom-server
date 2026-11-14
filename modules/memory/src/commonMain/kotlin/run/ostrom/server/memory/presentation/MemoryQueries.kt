// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory.presentation

import run.ostrom.server.memory.domain.InvalidMemoryException
import run.ostrom.server.memory.domain.MemoryCursor
import run.ostrom.server.memory.domain.MemoryScope
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** A comma-separated tag filter. */
internal fun tagsOf(value: String?): List<String> =
    if (value == null) emptyList() else value.split(',').map { it.trim() }.filter { it.isNotEmpty() }

/** A limit from the query: a number of at least 1 (the contract's minimum), or the default. */
internal fun limitOf(value: String?, default: Int): Int =
    value?.let { it.toIntOrNull()?.takeIf { limit -> limit >= 1 } ?: throw InvalidMemoryException(LIMIT) } ?: default

private const val LIMIT = "Use a number of at least 1 as the limit."

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
