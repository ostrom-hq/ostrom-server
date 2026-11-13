// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.domain

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Counts the execution time of an organization's cloud sessions against its plan's hours. Billing binds its own
 * meter (the billing period's counter, which raises the 80 % and 100 % alerts); without billing, the sessions' own
 * calendar-month counter ([RepositoryCloudUsageMeter]).
 */
interface CloudUsageMeter {
    /**
     * Reads the seconds used in the period the plan's hours apply to.
     *
     * @param orgId The organization.
     * @param now The time.
     *
     * @return The seconds.
     */
    suspend fun usedSeconds(orgId: Uuid, now: Instant): Long

    /**
     * Adds execution time.
     *
     * @param orgId The organization.
     * @param seconds The seconds to add, never negative.
     * @param newExecution Whether this is the first report of an execution (it counts one session).
     * @param now The time.
     */
    suspend fun record(orgId: Uuid, seconds: Long, newExecution: Boolean, now: Instant)
}

/** [CloudUsageMeter] on the sessions' own table, by calendar month (UTC). */
class RepositoryCloudUsageMeter(private val repository: CloudSessionsRepository) : CloudUsageMeter {
    override suspend fun usedSeconds(orgId: Uuid, now: Instant): Long = repository.usageSeconds(orgId, monthOf(now))

    override suspend fun record(orgId: Uuid, seconds: Long, newExecution: Boolean, now: Instant) {
        if (seconds > 0) repository.addUsage(orgId, monthOf(now), seconds)
    }
}
