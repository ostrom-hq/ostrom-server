// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.usage.infrastructure

import run.ostrom.server.kernel.launches.LaunchSpend
import run.ostrom.server.usage.domain.UsageRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * [LaunchSpend] on the ledger: what a launch's agent token spent through the gateway, read from as far back as a launch
 * lives (its agent token expires after 24 hours), so the read stays in the organization's recent events.
 *
 * @param repository The ledger.
 * @param clock The time.
 */
class LedgerLaunchSpend(private val repository: UsageRepository, private val clock: Clock) : LaunchSpend {
    override suspend fun spentMicros(orgId: Uuid, launchId: String): Long {
        val now = clock.now()
        val since = now - LAUNCH_LIFE_HOURS.hours
        return repository.launchSpentMicros(orgId, launchId, since, now + 1.minutes)
            ?: 0
    }
}
