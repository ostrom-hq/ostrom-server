// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.domain

import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.kernel.auth.MemberLogins
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * A preparation phase, or its failure, as the runner reports it (`POST /api/v1/cloud/runner/preparation`).
 *
 * @property sessionId The session the runner was started for.
 * @property epoch The execution the runner was started for.
 * @property phase The phase starting, or the one that failed.
 * @property failure The failed step, if the phase failed.
 */
data class RunnerPreparationReport(
    val sessionId: Uuid,
    val epoch: Long,
    val phase: PreparationPhase,
    val failure: PreparationFailure? = null,
)

/**
 * The runner side of a session's preparation (`runners.cloud.environment.v1`), as TeamCode's
 * `RunnerChannelUseCase.environment` and `progress`: only the runner of the current execution, while its session is
 * starting (`creating` or `resuming`) and its preparation has not failed.
 */
interface RunnerEnvironmentUseCase {
    /**
     * What the runner prepares: the session's recipe, the owner's Git identity and environment variables, resolved
     * on this call so a resume gets the owner's current values. A required variable the owner lacks, or a GitHub
     * link the recipe requires and the owner lacks, fails the preparation here.
     *
     * @param token The runner token, as presented.
     * @param request The session and execution the runner names.
     * @return The environment, holding secrets.
     * @throws RunnerUnauthenticatedException When no current execution holds the token.
     * @throws ExecutionFencedException When the token is not the one of the session and execution named.
     * @throws NotPreparingException When the session is not preparing.
     * @throws PreparationFailedException When the environment cannot be prepared; the session records why.
     */
    suspend fun environment(token: String, request: RunnerExecutionRequest): PreparedEnvironment

    /**
     * Records a phase (shown on the session), or a failure: the server writes the session's message, and the
     * reconciler stops the execution, then fails the session (first execution) or pauses it (resume).
     *
     * @param token The runner token, as presented.
     * @param report The report.
     * @return What the runner must do next: stop after a failure.
     * @throws RunnerUnauthenticatedException When no current execution holds the token.
     * @throws ExecutionFencedException When the token is not the one of the session and execution named.
     * @throws NotPreparingException When the session is not preparing.
     * @throws InvalidCloudSessionRequestException When the failure names no step of the recipe.
     */
    suspend fun progress(token: String, report: RunnerPreparationReport): HeartbeatAnswer
}

/**
 * [RunnerEnvironmentUseCase] on the repository, the owners' values, GitHub links and logins.
 *
 * @param repository The sessions.
 * @param tokens Hashes the presented token.
 * @param variables The members' environment variables.
 * @param github The members' GitHub access.
 * @param members Who is an active member.
 * @param logins The members' logins, for the Git identity.
 * @param clock The time.
 */
// Each refusal of the runner protocol is its own answer.
@Suppress("ThrowsCount", "LongParameterList")
class RunnerEnvironmentUseCaseImpl(
    private val repository: CloudSessionsRepository,
    private val tokens: RunnerTokens,
    private val variables: RunnerVariables,
    private val github: RunnerGitHubTokens,
    private val members: MemberDirectory,
    private val logins: MemberLogins,
    private val clock: Clock,
) : RunnerEnvironmentUseCase {
    override suspend fun environment(token: String, request: RunnerExecutionRequest): PreparedEnvironment {
        val session = preparing(token, request.sessionId, request.epoch)
        if (!members.isActiveMember(session.orgId, session.ownerId)) {
            fail(token, request, "The session's owner is no longer an active member of the organization.")
        }
        val spec = CloudEnvironmentRules.effective(session)
        val resolved = variables.of(session.orgId, session.ownerId)
        val missing = spec.requiredVariables.filter { it !in resolved.variables }
        // An assigned value that cannot be read now is an outage, not a missing assignment: trying again may work.
        val unreadable = missing.filter { it in resolved.unavailable }
        if (unreadable.isNotEmpty()) {
            fail(
                token,
                request,
                "Required environment variables of the owner are unavailable right now: ${unreadable.joinToString()}.",
                retryable = true,
            )
        }
        if (missing.isNotEmpty()) {
            val names = missing.joinToString()
            fail(token, request, "Required environment variables are not assigned to the owner: $names.")
        }
        if (spec.requireGitHub && session.githubAccess == GitHubAccessMode.NONE) {
            fail(
                token,
                request,
                "GitHub access is off for this session; its owner turns it on with " +
                    "ostrom cloud github ${session.id} --access owner.",
                retryable = true,
            )
        }
        val access = if (session.githubAccess == GitHubAccessMode.NONE) {
            RunnerGitHubToken.NotLinked
        } else {
            github.token(session.orgId, session.ownerId)
        }
        if (spec.requireGitHub && access !is RunnerGitHubToken.Linked) {
            val message = if (access is RunnerGitHubToken.NotLinked) {
                "The owner has not linked GitHub, which this session requires."
            } else {
                "The owner's GitHub access is unavailable right now."
            }
            fail(token, request, message, retryable = true)
        }
        val login = Uuid.parseOrNull(session.ownerId)?.let { logins.loginsOf(session.orgId, listOf(it))[it] }
            ?: session.ownerId
        return PreparedEnvironment(spec, CloudEnvironmentRules.gitIdentity(login, access, spec), resolved)
    }

    override suspend fun progress(token: String, report: RunnerPreparationReport): HeartbeatAnswer {
        repeat(MAX_ATTEMPTS) {
            val session = preparing(token, report.sessionId, report.epoch)
            val now = clock.now()
            val failure = report.failure
            val next = if (failure != null) {
                val spec = CloudEnvironmentRules.effective(session)
                PreparationFailures.validate(failure, spec)
                failed(
                    session,
                    PreparationFailures.message(failure, spec, session.epoch),
                    PreparationFailures.retryable(failure.step),
                )
            } else if (session.phase == report.phase.wire) {
                return answer(session)
            } else {
                session.copy(phase = report.phase.wire)
            }
            val stored = repository.compareAndSet(next.copy(updatedAt = now), now)
            if (stored != null) return answer(stored)
        }
        // Every attempt lost a race with the reconciler or a heartbeat: the runner reports again.
        throw ExecutionFencedException()
    }

    /** The session of the runner's current execution, while it prepares. */
    private suspend fun preparing(token: String, sessionId: Uuid, epoch: Long): CloudSession {
        val session = repository.findByRunnerTokenHash(tokens.hash(token)) ?: throw RunnerUnauthenticatedException()
        if (session.id != sessionId || session.epoch != epoch) throw ExecutionFencedException()
        val starting = session.state == SessionState.CREATING || session.state == SessionState.RESUMING
        if (!starting || session.desiredState != DesiredState.RUNNING || session.preparationFailed) {
            throw NotPreparingException()
        }
        return session
    }

    /** Records a failed preparation, then refuses the call; the reconciler stops the execution. */
    private suspend fun fail(
        token: String,
        request: RunnerExecutionRequest,
        message: String,
        retryable: Boolean = false,
    ): Nothing {
        repeat(MAX_ATTEMPTS) {
            val session = preparing(token, request.sessionId, request.epoch)
            val now = clock.now()
            if (repository.compareAndSet(failed(session, message, retryable).copy(updatedAt = now), now) != null) {
                throw PreparationFailedException()
            }
        }
        throw ExecutionFencedException()
    }

    private fun failed(session: CloudSession, message: String, retryable: Boolean): CloudSession = session.copy(
        phase = CloudEnvironmentRules.PREPARATION_FAILED,
        failure = SessionFailure(CloudEnvironmentRules.PREPARATION_FAILED, message, retryable),
    )

    /** A failed preparation stops the runner; otherwise it goes on (a pause or a delete comes with a heartbeat). */
    private fun answer(session: CloudSession): HeartbeatAnswer = HeartbeatAnswer(
        if (session.preparationFailed) RunnerInstruction.STOP else RunnerInstruction.RUN,
        session.state,
    )

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}

/** Nobody is an active member: a server built without the orgs module, where no session prepares (fail closed). */
object NoActiveMembers : MemberDirectory, MemberLogins {
    override suspend fun isActiveMember(orgId: Uuid, memberId: String): Boolean = false

    override suspend fun idsOf(orgId: Uuid, logins: Collection<String>): Map<String, Uuid> = emptyMap()

    override suspend fun loginsOf(orgId: Uuid, memberIds: Collection<Uuid>): Map<Uuid, String> = emptyMap()
}
