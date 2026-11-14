// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.domain

import kotlin.uuid.Uuid

/**
 * A member's environment variables, as the runner serves them to the agent (the secrets module's resolution, bound
 * by [run.ostrom.server.runners.RunnersModule]; without it, nobody has any).
 */
fun interface RunnerVariables {
    /**
     * @param orgId The organization.
     * @param memberId The member a session runs for.
     * @return Their values, resolved now: none for a member who is no longer active.
     */
    suspend fun of(orgId: Uuid, memberId: String): RunnerVariableValues
}

/** No environment variables: a server built without the secrets module. */
object NoRunnerVariables : RunnerVariables {
    override suspend fun of(orgId: Uuid, memberId: String): RunnerVariableValues = RunnerVariableValues.NONE
}

/**
 * The variables a runner gets.
 *
 * @property variables The values by name, never logged.
 * @property unavailable The assigned variables withheld because their value cannot be read now, by name.
 */
class RunnerVariableValues(val variables: Map<String, String>, val unavailable: List<String>) {
    override fun toString(): String = "RunnerVariableValues(${variables.keys.sorted()}, unavailable=$unavailable)"

    /** The empty answer. */
    companion object {
        /** No variable. */
        val NONE: RunnerVariableValues = RunnerVariableValues(emptyMap(), emptyList())
    }
}

/**
 * The runner of an execution asking for its call's subject (`POST /api/v1/cloud/runner/variables`).
 *
 * @property sessionId The session the runner was started for.
 * @property epoch The execution the runner was started for.
 */
data class RunnerExecutionRequest(val sessionId: Uuid, val epoch: Long)

/**
 * Hands the runner of a session that runs the environment variables of the member it runs for
 * (`runners.cloud.variables.v1`): the agent starts with them, and its Bash commands read them again while it runs.
 */
interface RunnerVariablesUseCase {
    /**
     * @param token The runner token, as presented.
     * @param request The session and execution the runner names.
     * @return The owner's variables, resolved on this call.
     * @throws RunnerUnauthenticatedException When no current execution holds the token.
     * @throws ExecutionFencedException When the token is not the one of the session and execution named.
     * @throws VariablesUnavailableException When the session no longer runs, or is being deleted.
     */
    suspend operator fun invoke(token: String, request: RunnerExecutionRequest): RunnerVariableValues
}

/**
 * [RunnerVariablesUseCase] on the repository and the members' values.
 *
 * @param repository The sessions.
 * @param tokens Hashes the presented token.
 * @param variables The members' environment variables.
 */
class RunnerVariablesUseCaseImpl(
    private val repository: CloudSessionsRepository,
    private val tokens: RunnerTokens,
    private val variables: RunnerVariables,
) : RunnerVariablesUseCase {
    override suspend fun invoke(token: String, request: RunnerExecutionRequest): RunnerVariableValues {
        val session = repository.findByRunnerTokenHash(tokens.hash(token)) ?: throw RunnerUnauthenticatedException()
        if (session.id != request.sessionId || session.epoch != request.epoch) throw ExecutionFencedException()
        // Only while it runs (`creating`, `resuming`, `running`, `pausing`), and not one being deleted.
        if (!session.state.holdsExecution || session.desiredState == DesiredState.TERMINATED) {
            throw VariablesUnavailableException()
        }
        return variables.of(session.orgId, session.ownerId)
    }
}
