// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory.domain

import kotlinx.coroutines.CancellationException
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * [MemoryCurationService].
 *
 * @param store What a person reads and answers.
 * @param runs The periodic run's reads and writes.
 * @param audit The organization's audit log.
 * @param clock The time of a run.
 * @param page How many organizations a run reads at a time.
 */
class MemoryCurationServiceImpl(
    private val store: MemoryCurationStore,
    private val runs: MemoryCurationRuns,
    private val audit: MemoryAudit,
    private val clock: Clock,
    private val page: Int = ORGANIZATIONS_PAGE,
) : MemoryCurationService {
    override suspend fun settings(caller: MemoryCaller): CurationSettings =
        store.settings(requireAdministrator(caller).orgId)

    override suspend fun setEnabled(caller: MemoryCaller, enabled: Boolean): CurationSettings {
        val settings = store.setEnabled(requireAdministrator(caller).orgId, enabled)
        val event = if (enabled) "memory.curation_enabled" else "memory.curation_disabled"
        audit.record(caller.orgId, caller.principal, event, caller.orgId, emptyMap())
        return settings
    }

    override suspend fun reviews(caller: MemoryCaller): List<MemoryReview> =
        store.reviews(requirePerson(caller), MemoryRules.CURATION_PAGE)

    override suspend fun resolve(caller: MemoryCaller, id: Uuid, apply: Boolean): MemoryReview {
        val resolved = store.resolve(requirePerson(caller), id, apply)
        val review = resolved.review
        if (resolved.superseded) {
            val run = "review:${caller.memberId}"
            auditSuperseded(caller.orgId, caller, review.olderId, review.newerId, run, "reviewed")
        }
        audit.record(caller, if (apply) "memory.review_applied" else "memory.review_dismissed", id)
        return review
    }

    override suspend fun superseded(caller: MemoryCaller): List<SupersededMemory> =
        store.superseded(requirePerson(caller), MemoryRules.CURATION_PAGE)

    override suspend fun restore(caller: MemoryCaller, id: Uuid): MemoryEntry {
        val restored = store.restore(requirePerson(caller), id) { entry ->
            if (entry.scope == MemoryScope.TEAM && !caller.admin && entry.authorId != caller.memberId) {
                throw MemoryCurationForbiddenException("Only the author or an administrator restores a team entry.")
            }
        }
        val replaced = restored.replacedId
        if (replaced != null) {
            auditSuperseded(caller.orgId, caller, replaced, id, "restore:${caller.memberId}", "restored")
        }
        val facts = if (replaced == null) emptyMap() else mapOf("replaced" to replaced.toString())
        audit.record(caller.orgId, caller.principal, "memory.restored", id, facts)
        return restored.entry
    }

    override suspend fun run(runId: String): CurationReport {
        var report = CurationReport(0, 0, 0, 0)
        var failure: Exception? = null
        var after: Uuid? = null
        do {
            val organizations = runs.organizations(after, page)
            for (orgId in organizations) {
                val room = MemoryRules.CURATION_SCOPES - report.scopes
                val (done, failed) = apart { curate(orgId, runId, room) }
                report += done
                failure = failure ?: failed
            }
            after = organizations.lastOrNull()
        } while (organizations.size == page)
        failure?.let { throw it }
        return report
    }

    /** One organization's run: its retention, then its due scopes within [room]. */
    private suspend fun curate(orgId: Uuid, runId: String, room: Int): CurationReport {
        val now = clock.now()
        val expired = runs.retain(orgId, now)
        expired.forEach { audit.record(orgId, MEMORY_ACTOR, "memory.expired", it, mapOf("run" to runId)) }
        val due = if (room > 0) runs.due(orgId, room, now) else emptyList()
        var superseded = 0
        var queued = 0
        for (scope in due) {
            val curated = runs.curate(orgId, scope, now)
            for ((older, newer) in curated.superseded) auditSuperseded(orgId, null, older, newer, runId, "duplicate")
            superseded += curated.superseded.size
            queued += curated.queued
        }
        if (due.isNotEmpty()) runs.recordRun(orgId, runId, now, superseded, queued)
        return CurationReport(due.size, superseded, queued, expired.size)
    }

    /** Audits [older] superseded by [newer], by [caller] or, for the run, the memory itself. */
    @Suppress("LongParameterList") // The audit's six facts.
    private suspend fun auditSuperseded(
        orgId: Uuid,
        caller: MemoryCaller?,
        older: Uuid,
        newer: Uuid,
        run: String,
        reason: String,
    ) {
        val facts = mapOf("by" to newer.toString(), "run" to run, "reason" to reason)
        audit.record(orgId, caller?.principal ?: MEMORY_ACTOR, "memory.superseded", older, facts)
    }

    private companion object {
        const val ORGANIZATIONS_PAGE = 100
    }
}

/** A person, as TeamCode's interactive login: a key, a token or an agent is refused. */
private fun requirePerson(caller: MemoryCaller): MemoryCaller {
    if (!caller.person || caller.agent) throw MemoryTeamForbiddenException("Curation is handled by a signed-in member.")
    return caller
}

private fun requireAdministrator(caller: MemoryCaller): MemoryCaller {
    if (!requirePerson(caller).admin) {
        throw MemoryCurationForbiddenException("Only an administrator changes the curation.")
    }
    return caller
}

/** One organization's run: its failure leaves the others done, and is thrown once every one was tried. */
private suspend fun apart(block: suspend () -> CurationReport): Pair<CurationReport, Exception?> = try {
    block() to null
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    CurationReport(0, 0, 0, 0) to error
}
