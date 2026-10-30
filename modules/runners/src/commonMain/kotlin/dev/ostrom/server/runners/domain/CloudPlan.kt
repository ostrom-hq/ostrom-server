// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners.domain

import kotlin.uuid.Uuid

/**
 * What an organization's plan allows for cloud sessions. The free plan's guards (Lyes, 30/10/2026): 5 agents at
 * once, 300 hours a month, a pause without requests to the model, a limited outbound network and a maximum duration.
 *
 * @property maxRunningSessions Sessions that may hold an execution at once (creating, running, pausing, resuming).
 * @property monthlyHours Execution hours per calendar month (UTC); null for unlimited.
 * @property idleTimeoutMinutes Minutes without a request to the model nor terminal input before a session pauses.
 * @property maxRunMinutes Minutes one execution may run before it pauses.
 * @property restrictedEgress Whether pods get the restricted outbound network.
 * @property available Whether the organization may run cloud sessions at all: on a shared cluster they are open only
 *   to the organizations of `OSTROM_RUNNERS_ALLOWED_ORGS` (audit S2-10), whatever their plan.
 */
data class CloudPlan(
    val maxRunningSessions: Int,
    val monthlyHours: Int?,
    val idleTimeoutMinutes: Int,
    val maxRunMinutes: Int,
    val restrictedEgress: Boolean,
    val available: Boolean,
) {
    /** The limits a new session of this plan runs under. */
    fun sessionLimits(): SessionLimits = SessionLimits(idleTimeoutMinutes, maxRunMinutes, restrictedEgress)

    companion object {
        /** The free plan: 5 agents at once, 300 hours a month, 30 minutes idle, 4 hours per run, limited egress. */
        val FREE: CloudPlan = CloudPlan(
            maxRunningSessions = 5,
            monthlyHours = 300,
            idleTimeoutMinutes = 30,
            maxRunMinutes = 240,
            restrictedEgress = true,
            available = true,
        )
    }
}

/**
 * Gives the plan of an organization. The budgets module (billing) will bind its own implementation; until then
 * every organization is on [CloudPlan.FREE].
 */
interface CloudPlanProvider {
    /**
     * Returns the plan of an organization.
     *
     * @param orgId The organization.
     *
     * @return Its cloud plan.
     */
    suspend fun planFor(orgId: Uuid): CloudPlan
}

/** Every organization on the free plan. */
class FreeCloudPlanProvider : CloudPlanProvider {
    override suspend fun planFor(orgId: Uuid): CloudPlan = CloudPlan.FREE
}

/**
 * Which organizations may run cloud sessions on this server (audit S2-10: closed by default on a shared cluster).
 *
 * @property all Every organization (a self-hosted or single-user server).
 * @property orgs The organizations allowed when [all] is false.
 */
data class AllowedOrgs(val all: Boolean, val orgs: Set<Uuid>) {
    /** Whether [orgId] is allowed. */
    operator fun contains(orgId: Uuid): Boolean = all || orgId in orgs

    companion object {
        /** No organization: the default. */
        val NONE = AllowedOrgs(all = false, orgs = emptySet())
    }
}

/**
 * Closes cloud sessions to the organizations [allowed] does not name, whatever [delegate] says of their plan.
 *
 * @param allowed The organizations allowed.
 * @param delegate The plans (the free plan until billing binds its own).
 */
class AllowlistedCloudPlanProvider(
    private val allowed: AllowedOrgs,
    private val delegate: CloudPlanProvider,
) : CloudPlanProvider {
    override suspend fun planFor(orgId: Uuid): CloudPlan {
        val plan = delegate.planFor(orgId)
        return if (orgId in allowed) plan else plan.copy(available = false)
    }
}
