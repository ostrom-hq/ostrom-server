// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.sessions

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Whether answers to a session's waits wait for its agent: resolved since an instant and not handed over yet. The
 * runners module asks it before the idle guard pauses a cloud session, so an answer given while the session ran idle
 * (its agent at its prompt, no Stop hook holding it) is not left until someone resumes it: the execution starts again
 * and its runner hands the answer over as the first message. The automations module binds it; unbound, none waits.
 */
fun interface PendingWaitAnswers {
    /**
     * Whether one waits.
     *
     * @param orgId The organization.
     * @param sessionId The session the waits were asked in.
     * @param since Only answers resolved after this instant (the start of the execution that runs): an older one was
     *   offered to it when it started.
     *
     * @return True when an answer resolved after [since] was not handed to an agent yet.
     */
    suspend fun answeredSince(orgId: Uuid, sessionId: String, since: Instant): Boolean
}
