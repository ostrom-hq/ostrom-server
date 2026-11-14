// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory.domain

import kotlin.uuid.Uuid

/**
 * The memory's curation (TeamCode #273, docs/memory.md "Curation"): off per organization until an administrator turns
 * it on, a periodic pass that never calls a model, a review queue for what it does not decide alone, restorable
 * superseded entries and the retention, which applies to every organization. A person only: a key, a token or an agent
 * never reaches it.
 */
interface MemoryCurationService {
    /**
     * The organization's setting and what its last run did; an administrator's.
     *
     * @throws MemoryTeamForbiddenException For anyone but a person.
     * @throws MemoryCurationForbiddenException For a member who does not administer.
     */
    suspend fun settings(caller: MemoryCaller): CurationSettings

    /** Turns the curation on or off; audited `memory.curation_enabled` or `memory.curation_disabled`. */
    suspend fun setEnabled(caller: MemoryCaller, enabled: Boolean): CurationSettings

    /** The open reviews the caller may answer, oldest first. */
    suspend fun reviews(caller: MemoryCaller): List<MemoryReview>

    /** Applies or dismisses a review; audited `memory.review_applied` (with `memory.superseded`) or `…_dismissed`. */
    suspend fun resolve(caller: MemoryCaller, id: Uuid, apply: Boolean): MemoryReview

    /** The superseded entries the caller may restore, newest first. */
    suspend fun superseded(caller: MemoryCaller): List<SupersededMemory>

    /**
     * Restores a superseded entry; audited `memory.restored`.
     *
     * @throws MemoryCurationForbiddenException For an organization entry, unless its author or an administrator.
     */
    suspend fun restore(caller: MemoryCaller, id: Uuid): MemoryEntry

    /**
     * One run of the server's periodic work: the retention of every organization, then at most
     * [MemoryRules.CURATION_SCOPES] scopes of the organizations that curate. Every change is audited with [runId].
     */
    suspend fun run(runId: String): CurationReport
}
