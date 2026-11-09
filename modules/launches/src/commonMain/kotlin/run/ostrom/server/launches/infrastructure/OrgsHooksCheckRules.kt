// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.launches.infrastructure

import run.ostrom.server.gates.domain.services.GateNodeResolver
import run.ostrom.server.launches.domain.models.HooksCheck
import run.ostrom.server.launches.domain.services.HooksCheckRules
import run.ostrom.server.orgs.domain.DEFAULT_HOOKS_DEADLINE
import run.ostrom.server.orgs.domain.OrgsRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

/**
 * [HooksCheckRules] on the orgs module's `hooksDeadlineSeconds` setting and the gates module's [GateNodeResolver]: an
 * agent without hooks is stopped where the organization enforces a gate (its policy is `failMode: closed`, always), and
 * left to run where it enforces none, so that a slow start never stops a workstation without a gate.
 *
 * Whether a gate is enforced compiles the organization's gates catalogue, which a launch must not do each time: the
 * answer is kept per organization, repository and pinned package (the gates' [GateNodeResolver.policyStamp]), so a new
 * package is a new key and no answer is ever stale on a timer. Without a pinned package nothing is kept. The wait is
 * read at each launch: one row.
 *
 * @param orgs Reads the organization's setting.
 * @param gates Tells whether the organization enforces a gate, and under which package.
 * @param maxEntries How many answers are kept; past it the cache starts again.
 */
class OrgsHooksCheckRules(
    private val orgs: OrgsRepository,
    private val gates: GateNodeResolver,
    private val maxEntries: Int = MAX_ENTRIES,
) : HooksCheckRules {
    private val lock = Mutex()
    private val kept = mutableMapOf<Triple<Uuid, String?, String>, Boolean>()

    override suspend fun check(orgId: Uuid, repo: String?): HooksCheck = HooksCheck(
        afterSeconds = orgs.get(orgId)?.hooksDeadlineSeconds ?: DEFAULT_HOOKS_DEADLINE,
        stopAgent = enforced(orgId, repo),
    )

    private suspend fun enforced(orgId: Uuid, repo: String?): Boolean {
        val stamp = gates.policyStamp(orgId, repo)
        val key = stamp?.let { Triple(orgId, repo, it) }
        val known = key?.let { lock.withLock { kept[it] } }
        if (known != null) return known
        val answer = gates.enforcesAny(orgId, repo)
        if (key != null) {
            lock.withLock {
                if (kept.size >= maxEntries) kept.clear()
                kept[key] = answer
            }
        }
        return answer
    }

    /** The bound of the cache. */
    companion object {
        /** How many organization and repository pairs are kept. */
        const val MAX_ENTRIES: Int = 1000
    }
}
