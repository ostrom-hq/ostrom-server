// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.launches.infrastructure

import dev.ostrom.server.gates.domain.services.GateNodeResolver
import dev.ostrom.server.launches.domain.models.HooksCheck
import dev.ostrom.server.launches.domain.services.HooksCheckRules
import dev.ostrom.server.orgs.domain.DEFAULT_HOOKS_DEADLINE
import dev.ostrom.server.orgs.domain.OrgsRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [HooksCheckRules] on the orgs module's `hooksDeadlineSeconds` setting and the gates module's [GateNodeResolver]: an
 * agent without hooks is stopped where the organization enforces a gate (its policy is `failMode: closed`, always), and
 * left to run where it enforces none, so that a slow start never stops a workstation without a gate.
 *
 * Whether a gate is enforced compiles the organization's gates catalogue, which a launch must not do each time: the
 * answer is kept for [ttl] per organization and repository, so a change of workflow reaches the next launches within
 * that time (it only decides whether an agent without hooks is stopped). The wait is read at each launch: one row.
 *
 * @param orgs Reads the organization's setting.
 * @param gates Tells whether the organization enforces a gate.
 * @param clock The clock.
 * @param ttl How long an answer of the gates is kept.
 * @param maxEntries How many answers are kept; past it the cache starts again.
 */
class OrgsHooksCheckRules(
    private val orgs: OrgsRepository,
    private val gates: GateNodeResolver,
    private val clock: Clock,
    private val ttl: Duration = 1.minutes,
    private val maxEntries: Int = MAX_ENTRIES,
) : HooksCheckRules {
    private val lock = Mutex()
    private val kept = mutableMapOf<Pair<Uuid, String?>, Pair<Instant, Boolean>>()

    override suspend fun check(orgId: Uuid, repo: String?): HooksCheck = HooksCheck(
        afterSeconds = orgs.get(orgId)?.hooksDeadlineSeconds ?: DEFAULT_HOOKS_DEADLINE,
        stopAgent = enforced(orgId, repo),
    )

    private suspend fun enforced(orgId: Uuid, repo: String?): Boolean {
        val now = clock.now()
        val key = orgId to repo
        lock.withLock { kept[key] }?.let { (at, answer) -> if (now < at + ttl) return answer }
        val answer = gates.enforcesAny(orgId, repo)
        lock.withLock {
            if (kept.size >= maxEntries) kept.clear()
            kept[key] = now to answer
        }
        return answer
    }

    /** The bound of the cache. */
    companion object {
        /** How many organization and repository pairs are kept. */
        const val MAX_ENTRIES: Int = 1000
    }
}
