// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.cloud.domain.CloudRuntime
import run.ostrom.server.cloud.domain.CloudRuntimeSource
import run.ostrom.server.runners.domain.MemberSessionCaps
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * [MemberSessionCaps] from the instance's cloud runtime (`maxRunningSessionsPerMember`, `maxRetainedSessionsPerMember`,
 * set in the console), read at most every 30 seconds by each replica, as [CloudSettingsAutomationLimits]: the caps are
 * asked for on every creation, transfer and start, and a change takes at most that long to reach a replica.
 *
 * @param runtime The cloud runtime.
 * @param clock The clock of the cache.
 * @param ttl How long a read holds.
 */
internal class CloudRuntimeMemberSessionCaps(
    private val runtime: CloudRuntimeSource,
    private val clock: Clock = Clock.System,
    private val ttl: Duration = 30.seconds,
) : MemberSessionCaps {
    private class Read(val at: Instant, val runtime: CloudRuntime)

    private val lock = Mutex()
    private var read: Read? = null

    override suspend fun runningLimit(): Int = current().runningLimit

    override suspend fun retainedLimit(): Int = current().retainedLimit

    private suspend fun current(): CloudRuntime {
        val now = clock.now()
        lock.withLock { read }?.takeIf { now - it.at < ttl }?.let { return it.runtime }
        return runtime.current().also { lock.withLock { read = Read(now, it) } }
    }
}
