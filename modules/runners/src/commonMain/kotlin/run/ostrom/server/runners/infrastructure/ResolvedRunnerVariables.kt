// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.runners.domain.RunnerVariableValues
import run.ostrom.server.runners.domain.RunnerVariables
import run.ostrom.server.secrets.domain.ResolveEnvironmentUseCase
import kotlin.uuid.Uuid

/**
 * [RunnerVariables] on the secrets module's resolution: read from the database on every call, so a change applies at
 * the runner's next ask.
 *
 * @param resolve The members' environment values.
 */
class ResolvedRunnerVariables(private val resolve: ResolveEnvironmentUseCase) : RunnerVariables {
    override suspend fun of(orgId: Uuid, memberId: String): RunnerVariableValues {
        // An owner id that is no member's names nobody: no value.
        val member = Uuid.parseOrNull(memberId) ?: return RunnerVariableValues.NONE
        val values = resolve(orgId, member)
        return RunnerVariableValues(values.values.mapValues { it.value.reveal() }, values.unavailable)
    }
}
