// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.workflowruns.domain.usecases

import run.ostrom.core.api.models.CheckAttestation
import run.ostrom.core.api.models.CheckRun
import run.ostrom.core.api.models.ReportCheckRequest
import run.ostrom.server.gates.domain.models.ActorKind
import run.ostrom.server.gates.domain.models.GateActor
import run.ostrom.server.workflowruns.domain.models.InvalidRunException
import run.ostrom.server.workflowruns.domain.models.RunSessionNotFoundException
import run.ostrom.server.workflowruns.domain.services.RunLaunches
import run.ostrom.server.workflowruns.domain.services.RunSessions
import kotlin.uuid.Uuid

/**
 * Records a check run the runner of a cloud session reports with its own token (decision 13:23, S2-49): the agent
 * cannot read that token, so the run is `isolated_runner` in the receipt, where a run of the launcher's hooks is only
 * the workstation's declaration. The runner runs the text the hook sends: a run of any other script than the package's
 * is recorded as `tampered` and stays the workstation's declaration (AUDIT-SECU-RUNNER-2 R1).
 */
interface ReportRunnerCheckUseCase {
    /**
     * Records the run.
     *
     * @param orgId The organization of the runner's cloud session.
     * @param cloudSessionId The cloud session whose current execution the runner token belongs to.
     * @param report What the runner ran: the workflow session it ran for is one of a launch its runner opened.
     *
     * @return The run as stored.
     *
     * @throws InvalidRunException When the report names no session.
     * @throws RunSessionNotFoundException When the session is not one of this cloud session's launches.
     */
    suspend operator fun invoke(orgId: Uuid, cloudSessionId: Uuid, report: ReportCheckRequest): CheckRun
}

/**
 * [ReportRunnerCheckUseCase] on the reports of the hooks: the run is checked as the agent's would be (the session's
 * package, its script, its outcome), and recorded as the runner's.
 *
 * @param sessions The workflow sessions.
 * @param launches Which cloud session opened a launch.
 * @param reportCheck Records a run.
 */
class ReportRunnerCheckUseCaseImpl(
    private val sessions: RunSessions,
    private val launches: RunLaunches,
    private val reportCheck: ReportCheckUseCase,
) : ReportRunnerCheckUseCase {
    override suspend fun invoke(orgId: Uuid, cloudSessionId: Uuid, report: ReportCheckRequest): CheckRun {
        val sessionId = report.sessionId ?: throw InvalidRunException("sessionId: the runner reports for a session.")
        val session = sessions.find(orgId, sessionId)
        val launchId = session?.launchId
        val opener = launchId?.let { launches.cloudSession(orgId, it) }
        // A session of the CLI, or of another cloud session, is not this runner's: answered as absent.
        if (session == null || opener != cloudSessionId) throw RunSessionNotFoundException()
        val actor = GateActor(
            "agent-$launchId",
            orgId,
            ActorKind.AGENT_SESSION,
            launchId = launchId,
            personId = session.dev,
        )
        return reportCheck.attested(actor, report, CheckAttestation.ISOLATED_RUNNER)
    }
}
