// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.automations.domain.AutomationLimits
import run.ostrom.server.cloud.domain.CloudSettings
import run.ostrom.server.cloud.domain.CloudSettingsSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The cap of running automation runs per member, as the organization's cloud settings
 * (`maxRunningAutomationsPerMember`, set in the console), read at most every 30 seconds per organization by each
 * replica: the cap is asked for on every run that starts, and a change takes at most that long to reach a replica.
 *
 * @param settings The cloud settings; null without the cloud module, where the default cap holds.
 * @param clock The clock of the cache.
 */
internal class CloudSettingsAutomationLimits(
    private val settings: CloudSettingsSource?,
    private val clock: Clock = Clock.System,
) : AutomationLimits {
    private class Read(val at: Instant, val cap: Int)

    private val lock = Mutex()
    private val reads = mutableMapOf<Uuid, Read>()

    override suspend fun maxRunningPerMember(orgId: Uuid): Int {
        if (settings == null) return CloudSettings.DEFAULT_MAX_RUNNING_AUTOMATIONS
        val now = clock.now()
        lock.withLock { reads[orgId] }?.takeIf { now - it.at < TTL }?.let { return it.cap }
        val cap = settings.current(orgId).maxRunningAutomationsPerMember
        lock.withLock { reads[orgId] = Read(now, cap) }
        return cap
    }

    private companion object {
        val TTL = 30.seconds
    }
}
