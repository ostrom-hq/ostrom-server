// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory.domain

import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Why a pair is queued for a person: equal text curation does not decide alone, or one title with two texts. */
enum class ReviewKind(val wire: String) {
    DUPLICATE("duplicate"),
    SAME_TITLE("same_title"),
}

/**
 * A pair of entries curation did not decide by itself: applying it supersedes [olderId] by [newerId].
 *
 * @property ownerId The member of a personal pair; null for the organization's.
 */
data class MemoryReview(
    val id: Uuid,
    val scope: MemoryScope,
    val ownerId: String?,
    val kind: ReviewKind,
    val reason: String,
    val olderId: Uuid,
    val olderTitle: String,
    val olderOrigin: MemoryOrigin,
    val newerId: Uuid,
    val newerTitle: String,
    val newerOrigin: MemoryOrigin,
    val createdAt: Instant,
)

/** An entry curation replaced by [supersededById], kept and restorable for [MemoryRules.SUPERSEDED_DAYS] days. */
data class SupersededMemory(
    val id: Uuid,
    val scope: MemoryScope,
    val ownerId: String?,
    val title: String,
    val summary: String,
    val authorId: String,
    val supersededById: Uuid?,
    val supersededAt: Instant,
)

/** An organization's curation setting, off until an administrator turns it on, and what its last run did. */
data class CurationSettings(
    val enabled: Boolean,
    val lastRunAt: Instant? = null,
    val lastRunId: String? = null,
    val lastSuperseded: Int = 0,
    val lastQueued: Int = 0,
)

/** What a run did: the scopes it read, the entries it superseded, the pairs it queued, the expired ones deleted. */
data class CurationReport(val scopes: Int, val superseded: Int, val queued: Int, val expired: Int) {
    /** Both runs' counts. */
    operator fun plus(other: CurationReport): CurationReport = CurationReport(
        scopes + other.scopes,
        superseded + other.superseded,
        queued + other.queued,
        expired + other.expired,
    )
}

/**
 * One scope curation reads alone: the organization's entries ([ownerId] empty), or one member's personal ones, so a
 * personal entry is only ever compared with its owner's.
 */
data class CurationScope(val scope: MemoryScope, val ownerId: String)

/** What curating one scope changed: each superseded entry with the newer one that replaced it, the pairs queued. */
data class CuratedScope(val superseded: List<Pair<Uuid, Uuid>>, val queued: Int)

/** A review answered, and whether its older entry was superseded by it. */
data class ResolvedReview(val review: MemoryReview, val superseded: Boolean)

/** A restored entry, and the one that replaced it when it was still active (now superseded in its turn). */
data class RestoredMemory(val entry: MemoryEntry, val replacedId: Uuid?)

/**
 * What a person reads and answers of the curation, on the memory's tables (TeamCode's `MemoryCurationStore`). A
 * personal review or superseded copy is its owner's alone, administrators included; the organization's are its
 * administrators' (and, for restoring, its author's).
 */
interface MemoryCurationStore {
    /** The organization's setting; off when it was never set. */
    suspend fun settings(orgId: Uuid): CurationSettings

    /** Turns the curation on or off. */
    suspend fun setEnabled(orgId: Uuid, enabled: Boolean): CurationSettings

    /** Open reviews, oldest first: the caller's personal ones, and the organization's for an administrator. */
    suspend fun reviews(caller: MemoryCaller, limit: Int): List<MemoryReview>

    /**
     * Applies (the older entry is superseded by the newer) or dismisses (both stay, never queued again) a review.
     *
     * @throws MemoryReviewNotFoundException When the caller has no such review.
     * @throws MemoryReviewClosedException When it was answered.
     * @throws MemoryReviewStaleException When applying it and one of its entries is no longer active.
     */
    suspend fun resolve(caller: MemoryCaller, id: Uuid, apply: Boolean): ResolvedReview

    /**
     * Superseded entries, newest first: the caller's personal ones, and the organization's for an administrator or
     * their author.
     */
    suspend fun superseded(caller: MemoryCaller, limit: Int): List<SupersededMemory>

    /**
     * Brings a superseded entry back; the entry that replaced it, if still active, becomes the superseded one. [check]
     * runs first on the entry, under its lock.
     *
     * @throws MemoryNotFoundException When the caller has no such superseded entry.
     * @throws MemoryLimitException When nothing leaves to make room and the memory is full.
     */
    suspend fun restore(caller: MemoryCaller, id: Uuid, check: (MemoryEntry) -> Unit): RestoredMemory
}

/**
 * The curation run's reads and writes, one organization and one scope per transaction (TeamCode's `curate`). It
 * never calls a model and deletes nothing it decides about: an older entry becomes `superseded`.
 */
interface MemoryCurationRuns {
    /** The organizations holding entries, by id after [after]. */
    suspend fun organizations(after: Uuid?, limit: Int): List<Uuid>

    /**
     * The scopes of an organization that curates, changed since their last pass or not curated for
     * [MemoryRules.RECURATE_DAYS] days, the least recently curated first; none when its curation is off.
     */
    suspend fun due(orgId: Uuid, room: Int, now: Instant): List<CurationScope>

    /** Curates one scope ([MemoryCurator]) and records when. */
    suspend fun curate(orgId: Uuid, scope: CurationScope, now: Instant): CuratedScope

    /** Records what the organization's run did. */
    suspend fun recordRun(orgId: Uuid, runId: String, now: Instant, superseded: Int, queued: Int)

    /**
     * Deletes what is past its retention, curation on or off: the entries expired [MemoryRules.EXPIRED_DAYS] days ago,
     * the superseded ones after [MemoryRules.SUPERSEDED_DAYS] days, the answered reviews after
     * [MemoryRules.REJECTED_DAYS].
     *
     * @return The expired entries deleted.
     */
    suspend fun retain(orgId: Uuid, now: Instant): List<Uuid>
}
