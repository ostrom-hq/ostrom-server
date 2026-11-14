// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory.presentation

import run.ostrom.core.api.models.MemoryCuration
import run.ostrom.server.memory.domain.CurationSettings
import run.ostrom.server.memory.domain.MemoryReview
import run.ostrom.server.memory.domain.MemoryReviewNotFoundException
import run.ostrom.server.memory.domain.SupersededMemory
import kotlin.uuid.Uuid
import run.ostrom.core.api.models.MemoryReview as MemoryReviewDto
import run.ostrom.core.api.models.SupersededMemory as SupersededMemoryDto

/** A review's id from its path; anything else is a review nobody has (404). */
internal fun reviewId(value: String): Uuid = Uuid.parseOrNull(value) ?: throw MemoryReviewNotFoundException()

internal fun CurationSettings.toDto(): MemoryCuration =
    MemoryCuration(enabled, lastRunAt?.toString(), lastRunId, lastSuperseded, lastQueued)

internal fun MemoryReview.toDto(): MemoryReviewDto = MemoryReviewDto(
    id = id.toString(),
    scope = scope.wire,
    kind = kind.wire,
    reason = reason,
    olderId = olderId.toString(),
    olderTitle = olderTitle,
    olderOrigin = olderOrigin.wire,
    newerId = newerId.toString(),
    newerTitle = newerTitle,
    newerOrigin = newerOrigin.wire,
    createdAt = createdAt.toString(),
)

internal fun SupersededMemory.toDto(): SupersededMemoryDto = SupersededMemoryDto(
    id = id.toString(),
    scope = scope.wire,
    title = title,
    summary = summary,
    authorId = authorId,
    supersededById = supersededById?.toString(),
    supersededAt = supersededAt.toString(),
)
