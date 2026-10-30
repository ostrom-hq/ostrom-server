package dev.ostrom.server.billing.domain

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
 * @property maxConcurrentCloudAgents The cloud agents that may run at the same time.
 * @property agentHoursPerPeriod The cloud agent hours of a billing period, pooled across the organization.
 * @property sso Whether single sign-on (OIDC, SAML) may be configured.
 * @property policies Whether organization policies may be configured.
 * @property receiptRetentionDays How long receipts are kept.
 */
data class PlanLimits(
    val maxPeople: Int?,
    val maxConcurrentCloudAgents: Int?,
    val agentHoursPerPeriod: Int?,
    val sso: Boolean,
    val policies: Boolean,
    val receiptRetentionDays: Int?,
)

/**
 * The limits of each plan, in one place so that a pricing change is one line. Free is Lyes's decision of 30/10/2026
 * (5 people, 5 cloud agents, 300 hours, BYOK). The paid amounts of agents and hours are not decided yet: they follow
 * the study's recommendation (100 pooled hours per paid person), recorded in `.coord/QUESTIONS.md`.
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
    fun fromLookupKey(lookupKey: String?): Pair<Plan, BillingInterval>? {
        if (lookupKey == null) return null
        for (plan in Plan.entries.filter { it.selfServe }) {
            for (interval in BillingInterval.entries) {
                if (lookupKey(plan, interval) == lookupKey) return plan to interval
            }
        }
        return null
    }

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
                maxPeople = 5,
                maxConcurrentCloudAgents = 5,
                agentHoursPerPeriod = 300,
                sso = false,
                policies = false,
                receiptRetentionDays = 30,
            )
            Plan.TEAM -> PlanLimits(
                maxPeople = null,
                maxConcurrentCloudAgents = maxOf(5, 5 * people),
                agentHoursPerPeriod = maxOf(300, 100 * people),
                sso = false,
                policies = false,
                receiptRetentionDays = 90,
            )
            Plan.BUSINESS -> PlanLimits(
                maxPeople = null,
                maxConcurrentCloudAgents = maxOf(10, 5 * people),
                agentHoursPerPeriod = maxOf(600, 150 * people),
                sso = true,
                policies = true,
                receiptRetentionDays = 365,
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
        maxConcurrentCloudAgents = null,
        agentHoursPerPeriod = null,
        sso = true,
        policies = true,
        receiptRetentionDays = null,
    )
}
