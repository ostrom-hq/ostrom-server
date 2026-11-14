// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory.fakes

import run.ostrom.server.memory.domain.CuratedScope
import run.ostrom.server.memory.domain.CurationScope
import run.ostrom.server.memory.domain.CurationSettings
import run.ostrom.server.memory.domain.MemoryCaller
import run.ostrom.server.memory.domain.MemoryCurationRuns
import run.ostrom.server.memory.domain.MemoryCurationStore
import run.ostrom.server.memory.domain.MemoryEntry
import run.ostrom.server.memory.domain.MemoryOrigin
import run.ostrom.server.memory.domain.MemoryReview
import run.ostrom.server.memory.domain.MemoryScope
import run.ostrom.server.memory.domain.MemoryStatus
import run.ostrom.server.memory.domain.ResolvedReview
import run.ostrom.server.memory.domain.RestoredMemory
import run.ostrom.server.memory.domain.ReviewKind
import run.ostrom.server.memory.domain.SupersededMemory
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The curation's requests in memory: one review, one superseded entry; it records what the service asked. */
class FakeCurationStore : MemoryCurationStore {
    val asked = mutableListOf<String>()
    var enabled = false
    var entryScope = MemoryScope.TEAM
    var replaced: Uuid? = null
    val review = MemoryReview(
        Uuid.random(), MemoryScope.PERSONAL, ADA, ReviewKind.SAME_TITLE, "why", Uuid.random(), "Old",
        MemoryOrigin.HUMAN, Uuid.random(), "New", MemoryOrigin.AGENT, FakeRepository.AT,
    )

    override suspend fun settings(orgId: Uuid) = CurationSettings(enabled)

    override suspend fun setEnabled(orgId: Uuid, enabled: Boolean): CurationSettings {
        this.enabled = enabled
        return CurationSettings(enabled)
    }

    override suspend fun reviews(caller: MemoryCaller, limit: Int): List<MemoryReview> {
        asked += "reviews $limit"
        return listOf(review)
    }

    override suspend fun resolve(caller: MemoryCaller, id: Uuid, apply: Boolean) = ResolvedReview(review, apply)

    override suspend fun superseded(caller: MemoryCaller, limit: Int): List<SupersededMemory> {
        asked += "superseded $limit"
        return emptyList()
    }

    override suspend fun restore(caller: MemoryCaller, id: Uuid, check: (MemoryEntry) -> Unit): RestoredMemory {
        val entry = MemoryEntry(
            id, entryScope, null, "T", "S", "C", emptyList(), emptyList(), MemoryStatus.ACTIVE, MemoryOrigin.HUMAN,
            BEN, null, null, null, 2, FakeRepository.AT, FakeRepository.AT, null, 0,
        )
        check(entry)
        return RestoredMemory(entry, replaced)
    }
}

/**
 * The run's reads and writes in memory: organizations by id, each with its [scopes] due and its [expired] entries;
 * one can be [broken].
 */
class FakeCurationRuns : MemoryCurationRuns {
    private val organizations = mutableListOf<Uuid>()
    val scopes = mutableMapOf<Uuid, List<CurationScope>>()
    val expired = mutableMapOf<Uuid, List<Uuid>>()
    val superseding = mutableMapOf<CurationScope, CuratedScope>()
    val broken = mutableSetOf<Uuid>()
    val rooms = mutableListOf<Pair<Uuid, Int>>()
    val recorded = mutableListOf<String>()
    val retained = mutableListOf<Uuid>()

    fun add(orgId: Uuid) {
        organizations += orgId
    }

    override suspend fun organizations(after: Uuid?, limit: Int): List<Uuid> =
        organizations.sortedBy { it.toString() }.filter { after == null || it.toString() > after.toString() }
            .take(limit)

    override suspend fun due(orgId: Uuid, room: Int, now: Instant): List<CurationScope> {
        rooms += orgId to room
        return scopes[orgId].orEmpty().take(room)
    }

    override suspend fun curate(orgId: Uuid, scope: CurationScope, now: Instant): CuratedScope =
        superseding[scope] ?: CuratedScope(emptyList(), 0)

    override suspend fun recordRun(orgId: Uuid, runId: String, now: Instant, superseded: Int, queued: Int) {
        recorded += "$orgId $runId $superseded $queued"
    }

    override suspend fun retain(orgId: Uuid, now: Instant): List<Uuid> {
        retained += orgId
        if (orgId in broken) error("broken")
        return expired[orgId].orEmpty()
    }
}
