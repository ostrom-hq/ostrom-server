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
 * @param start Where a run starts among the organizations, random by default: it goes on to the last one, then from
 *   the first back to it, so that no organization always comes last for the run's scopes.
 */
class MemoryCurationServiceImpl(
    private val store: MemoryCurationStore,
    private val runs: MemoryCurationRuns,
    private val audit: MemoryAudit,
    private val clock: Clock,
    private val page: Int = ORGANIZATIONS_PAGE,
    private val start: () -> Uuid = { Uuid.random() },
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
        forEachOrganization(start()) { orgId ->
            val room = MemoryRules.CURATION_SCOPES - report.scopes
            val (done, failed) = apart { curate(orgId, runId, room) }
            report += done
            failure = failure ?: failed
        }
        failure?.let { throw it }
        return report
    }

    /** Every organization once: those after [start], then from the first up to [start]. */
    private suspend fun forEachOrganization(start: Uuid, block: suspend (Uuid) -> Unit) {
        var after: Uuid? = start
        var wrapped = false
        var reading = true
        while (reading) {
            val organizations = runs.organizations(after, page)
            val within = if (wrapped) organizations.filter { it.toString() <= start.toString() } else organizations
            for (orgId in within) block(orgId)
            val more = organizations.size == page && within.size == organizations.size
            reading = more || !wrapped
            after = if (more) organizations.last() else null
            if (!more) wrapped = true
        }
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
        val actor = if (caller == null) MEMORY_ACTOR else caller.principal
        audit.record(orgId, actor, "memory.superseded", older, facts)
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
