// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.billing.domain

/**
 * The plans of Ostrom Cloud (grid decided by Lyes on 30/10/2026): Free, Team (35 USD per active person per month),
 * Business (65 USD, adds SSO, policies and long receipt retention) and Enterprise (on quote, self-hosted with a key).
 *
 * @property key The stable name used in the API and in Stripe metadata.
 */
enum class Plan(val key: String) {
    FREE("free"),
    TEAM("team"),
    BUSINESS("business"),
    ENTERPRISE("enterprise"),
    ;

    /** Whether the plan is bought through Stripe Checkout (Enterprise is invoiced by hand, Free is not bought). */
    val selfServe: Boolean get() = this == TEAM || this == BUSINESS

    /** Reading it back. */

    companion object {
        /**
         * Reads a plan from its key.
         *
         * @return The plan, or null for an unknown key.
         */
        fun fromKey(key: String): Plan? = entries.firstOrNull { it.key == key }
    }
}

/**
 * How often a paid plan is billed. Monthly is billed in arrears per active person (Stripe meter); yearly is billed in
 * advance for a committed number of people, at -20 %.
 *
 * @property key The stable name used in the API.
 */
enum class BillingInterval(val key: String) {
    MONTH("month"),
    YEAR("year"),
    ;

    /** Reading it back. */

    companion object {
        /**
         * Reads an interval from its key.
         *
         * @return The interval, or null for an unknown key.
         */
        fun fromKey(key: String): BillingInterval? = entries.firstOrNull { it.key == key }
    }
}

/**
 * What an organization may use. A null bound means unlimited.
 *
 * @property maxPeople The members the organization may have.
 * @property maxConcurrentCloudAgents The cloud agents that may run at the same time; [UNLIMITED_CLOUD_AGENTS] (0), the
 *   value of every plan, for no limit, as TeamCode's `maxConcurrent`.
 * @property agentHoursPerPeriod The cloud agent hours of a billing period, pooled across the organization.
 * @property sso Whether single sign-on (OIDC, SAML) may be configured.
 * @property policies Whether organization policies may be configured.
 * @property receiptRetentionDays How long receipts are kept.
 */
data class PlanLimits(
    val maxPeople: Int?,
    val maxConcurrentCloudAgents: Int,
    val agentHoursPerPeriod: Int?,
    val sso: Boolean,
    val policies: Boolean,
    val receiptRetentionDays: Int?,
)

/** [PlanLimits.maxConcurrentCloudAgents] without a limit: 0, as TeamCode's concurrency settings. */
const val UNLIMITED_CLOUD_AGENTS: Int = 0

// The grid decided on 30/10/2026 (`.coord/JOURNAL.md` 02:25): Free is 5 people and 300 agent hours; paid plans grow
// with the people billed. No plan caps the cloud agents running at once (Nathan, 14/11/2026, as TeamCode): the
// cluster's `maxRunningPods` and the organization's per-member cloud settings bound them.
private const val FREE_PEOPLE = 5
private const val FREE_HOURS = 300
private const val FREE_RETENTION_DAYS = 30
private const val TEAM_HOURS_PER_PERSON = 100
private const val TEAM_RETENTION_DAYS = 90
private const val BUSINESS_MIN_HOURS = 600
private const val BUSINESS_HOURS_PER_PERSON = 150
private const val BUSINESS_RETENTION_DAYS = 365

/**
 * The limits of each plan, in one place so that a pricing change is one line. Free is Lyes's decision of 30/10/2026
 * (5 people, 300 hours, BYOK); cloud agents at once are unlimited on every plan since Nathan's of 14/11/2026. The
 * paid amounts of hours are not decided yet: they follow the study's recommendation (100 pooled hours per paid
 * person), recorded in `.coord/QUESTIONS.md`.
 */
object PlanCatalog {
    /** The Stripe lookup key of the price of a plan and interval, as created by `ops/stripe/provision.py`. */
    fun lookupKey(plan: Plan, interval: BillingInterval): String {
        require(plan.selfServe) { "Only Team and Business have a Stripe price." }
        val suffix = if (interval == BillingInterval.MONTH) "monthly" else "yearly"
        return "ostrom_${plan.key}_$suffix"
    }

    /**
     * Reads a lookup key back.
     *
     * @return The plan and interval, or null when the key is not one of ours.
     */
    fun fromLookupKey(lookupKey: String?): Pair<Plan, BillingInterval>? = Plan.entries
        .filter { it.selfServe }
        .flatMap { plan -> BillingInterval.entries.map { plan to it } }
        .firstOrNull { (plan, interval) -> lookupKey(plan, interval) == lookupKey }

    /**
     * The limits of a plan for an organization billed for [billedPeople] people (at least one).
     *
     * @param plan The effective plan.
     * @param billedPeople The people the organization pays for this period: its active people, or its committed
     *   people on a yearly plan if more.
     */
    fun limits(plan: Plan, billedPeople: Int): PlanLimits {
        val people = maxOf(1, billedPeople)
        return when (plan) {
            Plan.FREE -> PlanLimits(
                maxPeople = FREE_PEOPLE,
                maxConcurrentCloudAgents = UNLIMITED_CLOUD_AGENTS,
                agentHoursPerPeriod = FREE_HOURS,
                sso = false,
                policies = false,
                receiptRetentionDays = FREE_RETENTION_DAYS,
            )
            Plan.TEAM -> PlanLimits(
                maxPeople = null,
                maxConcurrentCloudAgents = UNLIMITED_CLOUD_AGENTS,
                agentHoursPerPeriod = maxOf(FREE_HOURS, TEAM_HOURS_PER_PERSON * people),
                sso = false,
                policies = false,
                receiptRetentionDays = TEAM_RETENTION_DAYS,
            )
            Plan.BUSINESS -> PlanLimits(
                maxPeople = null,
                maxConcurrentCloudAgents = UNLIMITED_CLOUD_AGENTS,
                agentHoursPerPeriod = maxOf(BUSINESS_MIN_HOURS, BUSINESS_HOURS_PER_PERSON * people),
                sso = true,
                policies = true,
                receiptRetentionDays = BUSINESS_RETENTION_DAYS,
            )
            Plan.ENTERPRISE -> UNLIMITED
        }
    }

    /**
     * A price of the catalog, as created by `ops/stripe/provision.py` (USD cents, before tax).
     *
     * @property plan The plan.
     * @property interval The interval.
     * @property unitAmount Per active person per month, or per committed person per year.
     */
    data class Price(val plan: Plan, val interval: BillingInterval, val unitAmount: Long)

    /** The four prices of Team and Business; yearly is 12 months at -20 %. */
    val prices: List<Price> = listOf(
        Price(Plan.TEAM, BillingInterval.MONTH, 3_500),
        Price(Plan.TEAM, BillingInterval.YEAR, 33_600),
        Price(Plan.BUSINESS, BillingInterval.MONTH, 6_500),
        Price(Plan.BUSINESS, BillingInterval.YEAR, 62_400),
    )

    /** Enterprise, and a self-hosted instance whose license grants everything. */
    val UNLIMITED = PlanLimits(
        maxPeople = null,
        maxConcurrentCloudAgents = UNLIMITED_CLOUD_AGENTS,
        agentHoursPerPeriod = null,
        sso = true,
        policies = true,
        receiptRetentionDays = null,
    )
}
