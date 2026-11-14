// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.skills.infrastructure

import run.ostrom.server.runners.domain.AuthenticateRunnerUseCase
import run.ostrom.server.runners.domain.CheckRunnerTokenUseCase
import run.ostrom.server.skills.domain.CatalogConflictException
import run.ostrom.server.skills.domain.RunnerOwner
import run.ostrom.server.skills.domain.RunnerSessions
import kotlin.uuid.Uuid

/**
 * [RunnerSessions] on the runners module: the token of the session's current execution, which must still hold it
 * (`creating`, `resuming`, `running` or `pausing`), as the runner's other calls.
 *
 * @param checkToken Checks a runner token.
 * @param authenticate Checks a token against the execution it claims.
 */
class RunnersRunnerSessions(
    private val checkToken: CheckRunnerTokenUseCase,
    private val authenticate: AuthenticateRunnerUseCase,
) : RunnerSessions {
    override suspend fun check(token: String) = checkToken(token)

    override suspend fun owner(token: String, sessionId: Uuid, epoch: Long): RunnerOwner {
        val session = authenticate(token, sessionId, epoch)
        if (!session.state.holdsExecution) {
            throw CatalogConflictException("delivery_unavailable", "This cloud session runs no execution.")
        }
        return RunnerOwner(session.orgId, session.ownerId)
    }
}
