// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.skills.domain

import kotlin.uuid.Uuid

/**
 * The member a cloud session's runner installs skills and hooks for: the session's owner.
 *
 * @property orgId The session's organization.
 * @property memberId The member the session runs for.
 */
data class RunnerOwner(val orgId: Uuid, val memberId: String)

/**
 * The runners module's sessions, as the skill and hook deliveries read them (TeamCode's execution credential with
 * `skills:read` and `hooks:read`): a runner call names its execution with the execution's runner token.
 */
interface RunnerSessions {
    /**
     * Checks the runner token before a body is read.
     *
     * @throws run.ostrom.server.kernel.errors.OstromException 401 when no current execution holds it.
     */
    suspend fun check(token: String)

    /**
     * The owner of the session whose current execution is [sessionId] at [epoch], while that execution runs.
     *
     * @throws run.ostrom.server.kernel.errors.OstromException 401 for an unknown token, 409 `execution_fenced` for
     *   another execution, 409 `delivery_unavailable` for a session that holds no execution.
     */
    suspend fun owner(token: String, sessionId: Uuid, epoch: Long): RunnerOwner
}
