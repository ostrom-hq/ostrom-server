// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.sessions

import kotlin.uuid.Uuid

/**
 * Wakes a cloud session for the answer to one of its waits: TeamCode's message queued on the session, which resumes a
 * paused one (`docs/waits.md`, « Waking the session »). The answer stays on the wait, and the next execution's runner
 * hands it to the agent as its first message (`POST /api/v1/waits/wake`). The runners module binds it; automations
 * calls it once an answer, a time, an event or a timeout is committed. Unbound, no session is woken.
 */
fun interface CloudSessionWakes {
    /**
     * Resumes the cloud session [sessionId] names when it is paused and not ended, on its owner's behalf and within
     * the organization's limits. Idempotent: a session already running, or resumed by an earlier call, is left as it
     * is. A session that cannot resume stays paused, which is not an error.
     *
     * @param orgId The organization.
     * @param sessionId The session the wait was asked in: any session id, of a cloud session or not.
     * @param waitId The wait whose answer wakes it, for the audit log.
     *
     * @return Whether this call resumed it.
     */
    suspend fun wake(orgId: Uuid, sessionId: String, waitId: Uuid): Boolean
}
