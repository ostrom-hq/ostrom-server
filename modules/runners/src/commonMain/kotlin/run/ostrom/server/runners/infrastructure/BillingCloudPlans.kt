// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.billing.domain.GetAgentUsageUseCase
import run.ostrom.server.billing.domain.GetPlanLimitsUseCase
import run.ostrom.server.billing.domain.RecordAgentUsageUseCase
import run.ostrom.server.runners.domain.CloudPlan
import run.ostrom.server.runners.domain.CloudPlanProvider
import run.ostrom.server.runners.domain.CloudUsageMeter
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [CloudPlanProvider] on billing's plan limits: the cloud agents an organization may run at once and its hours per
 * billing period follow its plan (Free, Team, Business, Enterprise, or the license of a self-hosted instance). The
 * session guards (idle pause, longest run, restricted egress) are the free plan's for every plan: no other value is
 * decided.
 *
 * @param limits Billing's limits.
 */
class BillingCloudPlanProvider(private val limits: GetPlanLimitsUseCase) : CloudPlanProvider {
    override suspend fun planFor(orgId: Uuid): CloudPlan {
        val plan = limits(orgId)
        return CloudPlan.FREE.copy(
            maxRunningSessions = plan.maxConcurrentCloudAgents ?: CloudPlan.UNLIMITED_SESSIONS,
            monthlyHours = plan.agentHoursPerPeriod,
        )
    }
}

/**
 * [CloudUsageMeter] on billing's usage counter: the hours of the billing period, which billing compares with the
 * plan to raise the 80 % and 100 % alerts.
 *
 * @param read Reads the period's usage.
 * @param add Adds to it.
 */
class BillingCloudUsageMeter(
    private val read: GetAgentUsageUseCase,
    private val add: RecordAgentUsageUseCase,
) : CloudUsageMeter {
    override suspend fun usedSeconds(orgId: Uuid, now: Instant): Long = read(orgId).agentSeconds

    override suspend fun record(orgId: Uuid, seconds: Long, newExecution: Boolean, now: Instant) {
        add(orgId, seconds, if (newExecution) 1 else 0)
    }
}
