// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.billing.domain

import dev.ostrom.server.kernel.auth.OrgPrincipal
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Reads the limits of an organization: its plan on Ostrom Cloud, its license on a self-hosted instance. */
interface GetPlanLimitsUseCase {
    /** @return The limits in force now. */
    suspend operator fun invoke(orgId: Uuid): PlanLimits
}

/**
 * [GetPlanLimitsUseCase]. A self-hosted instance has no cloud costs to protect: people, agents and hours are
 * unlimited (or the license's people), and SSO, policies and long retention need a license that grants them.
 */
class GetPlanLimitsUseCaseImpl(
    private val repository: BillingRepository,
    private val licenses: LicenseProvider,
    private val settings: BillingSettings,
    private val clock: Clock,
) : GetPlanLimitsUseCase {
    override suspend fun invoke(orgId: Uuid): PlanLimits {
        if (!settings.stripeEnabled) return selfHostedLimits(licenses.current())
        val billing = repository.get(orgId)
        val period = billingPeriod(billing, clock.now())
        val plan = if (billing == null) Plan.FREE else billing.effectivePlan
        val people = maxOf(repository.countActivePeople(orgId, period.start), billing?.committedPeople ?: 0)
        return PlanCatalog.limits(plan, people)
    }

    private fun selfHostedLimits(license: LicenseGrant?): PlanLimits {
        val features = license?.features.orEmpty()
        return PlanLimits(
            maxPeople = license?.maxPeople,
            maxConcurrentCloudAgents = null,
            agentHoursPerPeriod = null,
            sso = "sso" in features,
            policies = "policies" in features,
            receiptRetentionDays = if ("retention" in features) null else 30,
        )
    }
}

/** Builds the console's view of an organization's billing. */
interface GetBillingOverviewUseCase {
    /** @throws BillingAccessDeniedException When [caller] is not a member. */
    suspend operator fun invoke(caller: OrgPrincipal, orgId: Uuid): BillingOverview
}

/** [GetBillingOverviewUseCase]. */
class GetBillingOverviewUseCaseImpl(
    private val repository: BillingRepository,
    private val limits: GetPlanLimitsUseCase,
    private val settings: BillingSettings,
    private val clock: Clock,
) : GetBillingOverviewUseCase {
    override suspend fun invoke(caller: OrgPrincipal, orgId: Uuid): BillingOverview {
        caller.requireMemberOf(orgId)
        val now = clock.now()
        val billing = repository.get(orgId) ?: OrgBilling(orgId = orgId, updatedAt = now)
        val period = billingPeriod(billing, now)
        return BillingOverview(
            billing = billing,
            effectivePlan = billing.effectivePlan,
            limits = limits(orgId),
            period = period,
            activePeople = repository.countActivePeople(orgId, period.start),
            usage = repository.usage(orgId, period.start),
            alerts = repository.alerts(orgId, period.start),
            stripeEnabled = settings.stripeEnabled,
        )
    }
}

/** Checks a use of the plan before it happens. Called by orgs (members), runners (cloud agents), identity (SSO). */
interface CheckEntitlementUseCase {
    /** @throws PlanLimitException With a message saying the limit, the use, and where to upgrade. */
    suspend operator fun invoke(orgId: Uuid, entitlement: Entitlement)
}

/** [CheckEntitlementUseCase]. */
class CheckEntitlementUseCaseImpl(
    private val repository: BillingRepository,
    private val limits: GetPlanLimitsUseCase,
    private val settings: BillingSettings,
    private val clock: Clock,
) : CheckEntitlementUseCase {
    override suspend fun invoke(orgId: Uuid, entitlement: Entitlement) {
        val limits = limits(orgId)
        val billing = repository.get(orgId)
        val words = LimitWords(billing, settings)
        when (entitlement) {
            is Entitlement.AddPerson -> checkPeople(limits, entitlement.currentPeople, words)
            is Entitlement.StartCloudAgent -> {
                checkConcurrentAgents(limits, entitlement.running, words)
                checkAgentHours(orgId, billing, limits, words)
            }
            Entitlement.Sso -> if (!limits.sso) {
                throw PlanLimitException("Single sign-on is part of the Business plan. ${words.upgrade}.")
            }
            Entitlement.Policies -> if (!limits.policies) {
                throw PlanLimitException("Organization policies are part of the Business plan. ${words.upgrade}.")
            }
        }
    }

    private fun checkPeople(limits: PlanLimits, people: Int, words: LimitWords) {
        val max = limits.maxPeople ?: return
        if (people >= max) {
            throw PlanLimitException(
                "${words.planName} includes $max people and this organization has $people. ${words.upgrade}.",
            )
        }
    }

    private fun checkConcurrentAgents(limits: PlanLimits, running: Int, words: LimitWords) {
        val max = limits.maxConcurrentCloudAgents ?: return
        if (running >= max) {
            throw PlanLimitException(
                "${words.planName} runs $max cloud agents at the same time and $running are running. " +
                    "Wait for one to finish, or upgrade at ${settings.billingPageUrl}.",
            )
        }
    }

    private suspend fun checkAgentHours(orgId: Uuid, billing: OrgBilling?, limits: PlanLimits, words: LimitWords) {
        val hours = limits.agentHoursPerPeriod ?: return
        val period = billingPeriod(billing, clock.now())
        if (repository.usage(orgId, period.start).agentSeconds >= hours * SECONDS_PER_HOUR) {
            val renewal = period.end.toLocalDateTime(TimeZone.UTC).date
            throw PlanLimitException(
                "This organization used the $hours cloud agent hours of its plan for this period " +
                    "(it renews on $renewal). ${words.upgrade}.",
            )
        }
    }
}

/** How a limit message names the plan and says where to go: the billing page on Ostrom Cloud, sales otherwise. */
private class LimitWords(billing: OrgBilling?, settings: BillingSettings) {
    val planName = if (settings.stripeEnabled) {
        "The ${(if (billing == null) Plan.FREE else billing.effectivePlan).displayName} plan"
    } else {
        "This instance's license"
    }
    val upgrade = if (settings.stripeEnabled) {
        "Upgrade at ${settings.billingPageUrl}"
    } else {
        "Contact sales@ostrom.run for a license"
    }
}

/** The plan's name as people read it. */
val Plan.displayName: String get() = key.take(1).uppercase() + key.drop(1)
