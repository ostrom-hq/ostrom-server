// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.workflowruns.domain.services

import kotlin.uuid.Uuid

/**
 * Whether a step's session still has a todo to finish before it delivers (TeamCode `WorkflowTodoGate`, `409
 * todo_open`). The todos module binds it once it stores the sessions' todos; until then nothing binds it and a delivery
 * is never refused for a todo.
 */
interface RunTodos {
    /**
     * Why the session may not deliver yet.
     *
     * @param orgId The organization.
     * @param sessionId The step's session.
     *
     * @return The reason (its open items), or null when its todo is done or it keeps none.
     */
    suspend fun refusal(orgId: Uuid, sessionId: String): String?
}
