// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.launches.domain.usecases

import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.audit.auditDetails
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.launches.LaunchSpend
import run.ostrom.server.launches.domain.models.InvalidLaunchLimitsException
import run.ostrom.server.launches.domain.models.Launch
import run.ostrom.server.launches.domain.models.LaunchEndedException
import run.ostrom.server.launches.domain.models.LaunchNotFoundException
import run.ostrom.server.launches.domain.models.Launcher
import run.ostrom.server.launches.domain.models.LaunchLimitsView
import run.ostrom.server.launches.domain.repositories.LaunchesRepository
import run.ostrom.server.secrets.domain.LaunchCaps
import kotlin.time.Clock

/** Reads the limits of a launch and what it spent (contract `getLaunchLimits`). */
interface GetLaunchLimitsUseCase {
    /**
     * Reads them: an owner or an admin reads any launch of the organization, anyone else their own.
     *
     * @param launcher The person, from their credential.
     * @param launchId The launch.
     *
     * @return The limits and what the launch spent.
     *
     * @throws LaunchNotFoundException When the launch is not one of the organization's, or not the person's own and
     *   they do not administer the organization: answered the same.
     */
    suspend operator fun invoke(launcher: Launcher, launchId: String): LaunchLimitsView
}

/**
 * [GetLaunchLimitsUseCase] on the launches and the ledger.
 *
 * @param launches The launches.
 * @param spend What a launch spent; null reads zero.
 */
class GetLaunchLimitsUseCaseImpl(
    private val launches: LaunchesRepository,
    private val spend: LaunchSpend? = null,
) : GetLaunchLimitsUseCase {
    override suspend fun invoke(launcher: Launcher, launchId: String): LaunchLimitsView {
        val launch = launches.find(launcher.orgId, launchId)
            ?.takeIf { it.userId == launcher.userId || LaunchLimitRules.administers(launcher.role) }
            ?: throw LaunchNotFoundException()
        return LaunchLimitsView(launch.caps, spend?.spentMicros(launcher.orgId, launch.id) ?: 0)
    }
}

/** Sets the limits of a launch (contract `setLaunchLimits`, TeamCode's `PUT /runs/{id}/policy`). */
interface SetLaunchLimitsUseCase {
    /**
     * Sets them; applied at the launch's next model call, on every replica.
     *
     * @param principal The authenticated caller, the actor of the audit line.
     * @param launcher The person, from their credential.
     * @param launchId The launch.
     * @param caps The new limits.
     *
     * @return The limits now in force and what the launch spent.
     *
     * @throws LaunchNotFoundException When the launch is not one of the organization's, or the person does not
     *   administer it: an agent token, a member and an unknown launch are answered alike (nothing leaks).
     * @throws InvalidLaunchLimitsException When a limit is out of its bounds.
     * @throws LaunchEndedException When the launch ended or expired.
     */
    suspend operator fun invoke(
        principal: AuthenticatedPrincipal,
        launcher: Launcher,
        launchId: String,
        caps: LaunchCaps,
    ): LaunchLimitsView
}

/**
 * [SetLaunchLimitsUseCase] on the launches and the audit log.
 *
 * @param launches The launches.
 * @param audit The organization's audit log.
 * @param clock The time.
 * @param spend What a launch spent; null reads zero.
 */
class SetLaunchLimitsUseCaseImpl(
    private val launches: LaunchesRepository,
    private val audit: OrgAuditTrail,
    private val clock: Clock,
    private val spend: LaunchSpend? = null,
) : SetLaunchLimitsUseCase {
    override suspend fun invoke(
        principal: AuthenticatedPrincipal,
        launcher: Launcher,
        launchId: String,
        caps: LaunchCaps,
    ): LaunchLimitsView {
        if (!LaunchLimitRules.administers(launcher.role)) throw LaunchNotFoundException()
        val launch = launches.find(launcher.orgId, launchId) ?: throw LaunchNotFoundException()
        LaunchLimitRules.check(caps)
        if (launch.expiresAt <= clock.now()) throw LaunchEndedException()
        if (!launches.setCaps(launcher.orgId, launch.id, caps)) throw LaunchNotFoundException()
        audit.record(
            OrgAuditRecord(
                launcher.orgId,
                principal,
                "launch.limits_updated",
                "launch",
                launch.id,
                auditDetails(
                    "budgetMicros" to caps.budgetMicros,
                    "maxConcurrent" to caps.maxConcurrent,
                    "paused" to caps.paused,
                ),
            ),
        )
        return LaunchLimitsView(caps, spend?.spentMicros(launcher.orgId, launch.id) ?: 0)
    }
}

/** The bounds of a launch's limits, TeamCode's: a budget up to 100 000 000 000 000 µUSD, 0 to 32 concurrent calls. */
object LaunchLimitRules {
    /** The largest budget. */
    const val MAX_BUDGET_MICROS: Long = 100_000_000_000_000

    /** The most concurrent calls a launch may be capped to. */
    const val MAX_CONCURRENT: Int = 32

    /** Whether a role administers the organization's launches: an owner or an admin. */
    fun administers(role: OrgRole): Boolean = role == OrgRole.OWNER || role == OrgRole.ADMIN

    /**
     * Checks the bounds.
     *
     * @param caps The limits.
     *
     * @throws InvalidLaunchLimitsException When one is out of its bounds.
     */
    fun check(caps: LaunchCaps) {
        if (caps.budgetMicros !in 0..MAX_BUDGET_MICROS) {
            throw InvalidLaunchLimitsException("budgetMicros: from 0 to $MAX_BUDGET_MICROS.")
        }
        if (caps.maxConcurrent !in 0..MAX_CONCURRENT) {
            throw InvalidLaunchLimitsException("maxConcurrent: from 0 to $MAX_CONCURRENT.")
        }
    }
}
