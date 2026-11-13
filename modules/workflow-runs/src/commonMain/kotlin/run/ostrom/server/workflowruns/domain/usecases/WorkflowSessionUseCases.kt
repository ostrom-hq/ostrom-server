// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.workflowruns.domain.usecases

import run.ostrom.core.api.models.WorkflowPendingDeliverable
import run.ostrom.core.api.models.WorkflowRun
import run.ostrom.core.api.models.WorkflowSessionPending
import run.ostrom.server.workflowruns.domain.engine.sessionOf
import run.ostrom.server.workflowruns.domain.models.NothingToDeliverException
import run.ostrom.server.workflowruns.domain.models.PlayedRun
import run.ostrom.server.workflowruns.domain.models.RunSessionNotFoundException
import run.ostrom.server.workflowruns.domain.repositories.RunEngineRepository
import run.ostrom.server.workflowruns.domain.services.RunLaunches
import run.ostrom.server.workflowruns.domain.services.StepSessions
import kotlinx.serialization.json.JsonElement
import kotlin.uuid.Uuid

/**
 * Who asks about the step of a session, as the routes read the credential.
 *
 * @property orgId The organization.
 * @property starter The login of the person a run must be started by: the person's own, or the one who launched the
 *   agent token.
 * @property launchId For the session's agent token, its launch: the session is the launch's own cloud session, whatever
 *   the request names.
 */
data class StepCaller(val orgId: Uuid, val starter: String, val launchId: String? = null)

/**
 * The run a session works for.
 *
 * @property sessionId The cloud session.
 * @property played The run, with its graph.
 */
data class SessionRun(val sessionId: Uuid, val played: PlayedRun)

/** `deliverWorkflowSession`: delivers for the agent step a session works on (TeamCode `deliverBySession`). */
interface DeliverWorkflowSessionUseCase {
    /**
     * Delivers.
     *
     * @param caller Who asks.
     * @param sessionId The cloud session a person names; an agent token's is its launch's.
     * @param output What is delivered.
     * @param person The person's id, for the audit log.
     *
     * @return The run after the delivery.
     *
     * @throws RunSessionNotFoundException When a person names a session that is neither theirs nor running their run.
     * @throws NothingToDeliverException When the session runs no step waiting for a delivery, and the refusals of
     *   [RunDeliveries.deliver].
     */
    suspend operator fun invoke(caller: StepCaller, sessionId: Uuid?, output: JsonElement, person: Uuid?): WorkflowRun
}

/**
 * `pendingWorkflowSession`: whether a session's agent step still waits for its delivery (TeamCode `pendingBySession`).
 */
interface PendingWorkflowSessionUseCase {
    /**
     * Reads it; writes nothing.
     *
     * @param caller Who asks.
     * @param sessionId The cloud session a person names; an agent token's is its launch's.
     *
     * @return Whether the step waits, and for what form.
     *
     * @throws RunSessionNotFoundException As in [DeliverWorkflowSessionUseCase].
     */
    suspend operator fun invoke(caller: StepCaller, sessionId: Uuid?): WorkflowSessionPending
}

/**
 * The run a session works for: the run whose latest visit is bound to the session, started by the caller.
 *
 * @param engine The runs.
 * @param steps The cloud sessions, to tell a person's own session from someone else's.
 * @param launches The cloud session of an agent token's launch.
 */
class SessionRuns(
    private val engine: RunEngineRepository,
    private val steps: StepSessions,
    private val launches: RunLaunches,
) {
    /**
     * Finds it.
     *
     * @param caller Who asks.
     * @param named The cloud session a person names; an agent token's is its launch's.
     *
     * @return The run, or null when the session is the caller's own and runs none of their runs (or the agent token's
     *   launch runs in no cloud session).
     *
     * @throws RunSessionNotFoundException When a person names a session that is neither: it does not exist or is
     *   another member's.
     */
    suspend fun of(caller: StepCaller, named: Uuid?): SessionRun? {
        val launch = caller.launchId
        val sessionId = (if (launch != null) launches.cloudSession(caller.orgId, launch) else named) ?: return null
        val played = engine.ofSession(caller.orgId, sessionId)?.takeIf { it.run.startedBy == caller.starter }
        // A run another member started is not told apart from a session that does not exist.
        if (played == null && launch == null && !steps.ownedBy(caller.orgId, sessionId, caller.starter)) {
            throw RunSessionNotFoundException()
        }
        return played?.let { SessionRun(sessionId, it) }
    }
}

/**
 * [DeliverWorkflowSessionUseCase] on the run of the session.
 *
 * @param runs Finds the run of a session.
 * @param deliveries Hands the delivery in, with the checks of `deliverRun`.
 */
class DeliverWorkflowSessionUseCaseImpl(
    private val runs: SessionRuns,
    private val deliveries: RunDeliveries,
) : DeliverWorkflowSessionUseCase {
    override suspend fun invoke(
        caller: StepCaller,
        sessionId: Uuid?,
        output: JsonElement,
        person: Uuid?,
    ): WorkflowRun {
        val found = runs.of(caller, sessionId) ?: throw NothingToDeliverException()
        return deliveries.deliver(caller.orgId, found.played, output, person, session = found.sessionId)
    }
}

/**
 * [PendingWorkflowSessionUseCase]: the step waits when the run's visit under way is the session's, of an agent node
 * that asks for a deliverable, and the session's agent asked nothing still open (a question to a person is not a
 * delivery forgotten).
 *
 * @param runs Finds the run of a session.
 * @param steps Whether the session's agent asked something still open.
 */
class PendingWorkflowSessionUseCaseImpl(
    private val runs: SessionRuns,
    private val steps: StepSessions,
) : PendingWorkflowSessionUseCase {
    override suspend fun invoke(caller: StepCaller, sessionId: Uuid?): WorkflowSessionPending {
        val (session, played) = runs.of(caller, sessionId) ?: return NOTHING
        val step = awaitedStep(played) ?: return NOTHING
        if (sessionOf(played.run.id, step.visit.seq) != session || steps.asked(caller.orgId, session)) return NOTHING
        return WorkflowSessionPending(
            needsAction = true,
            runId = played.run.id,
            nodeTitle = step.node.label,
            deliverable = WorkflowPendingDeliverable(step.deliverable.type, step.deliverable.domain),
        )
    }
}

// What a session that waits for no delivery is told.
private val NOTHING = WorkflowSessionPending(needsAction = false)
