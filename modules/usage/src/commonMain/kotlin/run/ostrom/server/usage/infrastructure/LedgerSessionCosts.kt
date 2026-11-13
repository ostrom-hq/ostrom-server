// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.usage.infrastructure

import run.ostrom.server.sessions.domain.services.SessionCosts
import run.ostrom.server.usage.domain.UsageRepository
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A session segment's cost, as the ledger measured it: what its launch's agent token spent through the gateway while
 * the segment ran (SECURITY-AUDIT S2-48: never the agent's figure). Signed into the segment's receipt.
 *
 * @param repository The ledger.
 */
class LedgerSessionCosts(private val repository: UsageRepository) : SessionCosts {
    override suspend fun costOf(orgId: Uuid, launchId: String?, from: Instant, to: Instant): Double? =
        launchId?.let { repository.launchSpentMicros(orgId, it, from, to) }?.let { it / MICROS_PER_USD }

    private companion object {
        const val MICROS_PER_USD = 1_000_000.0
    }
}
