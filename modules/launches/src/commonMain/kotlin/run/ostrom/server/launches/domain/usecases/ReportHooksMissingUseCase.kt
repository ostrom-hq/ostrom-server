// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.launches.domain.usecases

import run.ostrom.server.gates.domain.models.GateActor
import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.audit.auditDetails
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.launches.domain.models.HooksMissingReport
import run.ostrom.server.launches.domain.models.InvalidLaunchException
import run.ostrom.server.launches.domain.models.LaunchNotFoundException
import run.ostrom.server.launches.domain.repositories.LaunchesRepository

/**
 * Records that no hook of Ostrom ran in an agent session (audit CLI n°2, M3): the CLI waited for the first hook, saw
 * none, and says so, with whether it stopped the agent. The organization's audit log then holds `hooks.missing`
 * (`GET /api/v1/audit?event=hooks.missing`). Only the launch's own agent token reports it, as only it ends it.
 */
interface ReportHooksMissingUseCase {
    /**
     * Records the report.
     *
     * @param principal The authenticated caller, the actor of the audit line.
     * @param actor The caller as an actor: the launch of its agent token, or null when it is no live agent token.
     * @param launchId The launch.
     * @param report What the CLI saw.
     *
     * @throws LaunchNotFoundException When the caller is not that launch's agent token, or the launch is unknown.
     * @throws InvalidLaunchException When the wait is not from 0 to [MAX_WAITED_SECONDS] seconds.
     */
    suspend operator fun invoke(
        principal: AuthenticatedPrincipal,
        actor: GateActor?,
        launchId: String,
        report: HooksMissingReport,
    )

    /** The bounds of a report. */
    companion object {
        /** The longest wait a report names, in seconds: an hour, far above any organization's setting. */
        const val MAX_WAITED_SECONDS: Int = 3600
    }
}

/**
 * [ReportHooksMissingUseCase] on the launches and the organization's audit log.
 *
 * @param launches The launches, read in the caller's organization.
 * @param audit The organization's audit log.
 */
class ReportHooksMissingUseCaseImpl(
    private val launches: LaunchesRepository,
    private val audit: OrgAuditTrail,
) : ReportHooksMissingUseCase {
    override suspend fun invoke(
        principal: AuthenticatedPrincipal,
        actor: GateActor?,
        launchId: String,
        report: HooksMissingReport,
    ) {
        // The caller first: a credential that is not the launch's learns nothing, not even the bounds.
        val caller = actor?.takeIf { it.launchId == launchId } ?: throw LaunchNotFoundException()
        if (report.waitedSeconds !in 0..ReportHooksMissingUseCase.MAX_WAITED_SECONDS) {
            throw InvalidLaunchException("waitedSeconds: from 0 to ${ReportHooksMissingUseCase.MAX_WAITED_SECONDS}.")
        }
        val launch = launches.find(caller.orgId, launchId) ?: throw LaunchNotFoundException()
        val details = auditDetails(
            "agent" to launch.agent,
            "repo" to launch.repo,
            "waitedSeconds" to report.waitedSeconds,
            "stopped" to report.stopped,
        )
        audit.record(OrgAuditRecord(caller.orgId, principal, "hooks.missing", "launch", launch.id, details))
    }
}
