// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners

import dev.ostrom.server.billing.domain.GetAgentUsageUseCase
import dev.ostrom.server.billing.domain.GetPlanLimitsUseCase
import dev.ostrom.server.billing.domain.PlanLimits
import dev.ostrom.server.billing.domain.RecordAgentUsageUseCase
import dev.ostrom.server.billing.domain.Usage
import dev.ostrom.server.kernel.config.ConfigSource
import dev.ostrom.server.kernel.config.MapConfigSource
import dev.ostrom.server.runners.domain.CloudPlan
import dev.ostrom.server.runners.domain.CloudPlanProvider
import dev.ostrom.server.runners.domain.CloudSessionsRepository
import dev.ostrom.server.runners.domain.CloudUsageMeter
import dev.ostrom.server.runners.domain.RepositoryCloudUsageMeter
import dev.ostrom.server.runners.fakes.InMemoryCloudSessionsRepository
import dev.ostrom.server.runners.fakes.ORG_A
import dev.ostrom.server.runners.fakes.ORG_B
import dev.ostrom.server.runners.infrastructure.BillingCloudUsageMeter
import kotlinx.coroutines.test.runTest
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val PERIOD = Instant.parse("2026-10-15T00:00:00Z")

/** Billing's answers, set by the test, and what it was told. */
private class FakeBilling : GetPlanLimitsUseCase, RecordAgentUsageUseCase {
    var limits = PlanLimits(null, null, null, sso = true, policies = true, receiptRetentionDays = null)
    var seconds = 0L
    val recorded = mutableListOf<Triple<Uuid, Long, Long>>()

    override suspend fun invoke(orgId: Uuid): PlanLimits = limits

    val usage = object : GetAgentUsageUseCase {
        override suspend fun invoke(orgId: Uuid): Usage = Usage(PERIOD, seconds, 0)
    }

    override suspend fun invoke(orgId: Uuid, agentSeconds: Long, sessions: Long): Usage {
        recorded += Triple(orgId, agentSeconds, sessions)
        seconds += agentSeconds
        return Usage(PERIOD, seconds, sessions)
    }
}

class BillingCloudPlansTest {
    private val billing = FakeBilling()

    private fun koin(withBilling: Boolean): org.koin.core.Koin {
        val ports: Module = module {
            single<ConfigSource> { MapConfigSource(mapOf("OSTROM_RUNNERS_ALLOWED_ORGS" to ORG_A.toString())) }
            single<CloudSessionsRepository> { InMemoryCloudSessionsRepository() }
            if (withBilling) {
                single<GetPlanLimitsUseCase> { billing }
                single<GetAgentUsageUseCase> { billing.usage }
                single<RecordAgentUsageUseCase> { billing }
            }
        }
        return koinApplication {
            allowOverride(true)
            modules(RunnersModule.koinModule, ports)
        }.koin
    }

    @Test
    fun aPaidPlanGivesItsAgentsAndHoursWithTheFreeGuards() = runTest {
        val plans = koin(withBilling = true).get<CloudPlanProvider>()
        billing.limits = billing.limits.copy(maxConcurrentCloudAgents = 15, agentHoursPerPeriod = 300 * 3)
        assertEquals(CloudPlan.FREE.copy(maxRunningSessions = 15, monthlyHours = 900), plans.planFor(ORG_A))
        // Enterprise, or a self-hosted license: no limit.
        billing.limits = billing.limits.copy(maxConcurrentCloudAgents = null, agentHoursPerPeriod = null)
        val unlimited = plans.planFor(ORG_A)
        assertEquals(CloudPlan.UNLIMITED_SESSIONS, unlimited.maxRunningSessions)
        assertEquals(null, unlimited.monthlyHours)
        // The allowlist still closes the organizations it does not name, whatever their plan.
        assertFalse(plans.planFor(ORG_B).available)
    }

    @Test
    fun theHoursAreBillingsCounterAndEachExecutionCountsOneSession() = runTest {
        val meter = koin(withBilling = true).get<CloudUsageMeter>()
        assertIs<BillingCloudUsageMeter>(meter)
        meter.record(ORG_A, 20, newExecution = true, PERIOD)
        meter.record(ORG_A, 15, newExecution = false, PERIOD)
        assertEquals(listOf(Triple(ORG_A, 20L, 1L), Triple(ORG_A, 15L, 0L)), billing.recorded)
        assertEquals(35L, meter.usedSeconds(ORG_A, PERIOD))
    }

    @Test
    fun withoutBillingTheFreePlanAndTheMonthlyCounterApply() = runTest {
        val koin = koin(withBilling = false)
        assertEquals(CloudPlan.FREE, koin.get<CloudPlanProvider>().planFor(ORG_A))
        val meter = koin.get<CloudUsageMeter>()
        assertIs<RepositoryCloudUsageMeter>(meter)
        meter.record(ORG_A, 0, newExecution = true, PERIOD)
        meter.record(ORG_A, 40, newExecution = false, PERIOD)
        assertEquals(40L, meter.usedSeconds(ORG_A, PERIOD))
    }
}
