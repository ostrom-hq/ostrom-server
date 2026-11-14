// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.automations.infrastructure

import run.ostrom.server.automations.domain.Wait
import run.ostrom.server.automations.domain.WaitEvents
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.kernel.sessions.CloudSessionWakes
import kotlin.coroutines.cancellation.CancellationException

/**
 * [WaitEvents] that wake the session of a resolved wait, after [events] were told: TeamCode's message queued on the
 * session once the answer is stored (`docs/waits.md`, « Waking the session »). An answer, a time reached, an event
 * received and a timeout all resolve through [WaitEvents.resolved], once the change is committed; a paused cloud
 * session resumes ([CloudSessionWakes]), and its next execution's runner hands the answer to the agent. A wake that
 * fails is logged (its type only) and never fails the answer, which stays on the wait for the next execution.
 *
 * Not retried: the answer is the queued message here (`wake_pending` until an agent takes it), so a wake lost between
 * the commit and the resume (the replica stopped) leaves the session paused with its answer kept, as a session at its
 * cap does, until its owner resumes it. Retrying would need a flag of its own, since `wake_pending` stays set on a
 * session its owner pauses again on purpose, which a scan must not resume.
 *
 * @param events Told first, as before.
 * @param wakes Resumes a paused cloud session.
 * @param logger Where a wake that failed is reported.
 */
internal class CloudWakingWaitEvents(
    private val events: WaitEvents,
    private val wakes: CloudSessionWakes,
    private val logger: Logger,
) : WaitEvents {
    override suspend fun created(wait: Wait) = events.created(wait)

    override suspend fun resolved(wait: Wait) {
        events.resolved(wait)
        val session = wait.sessionId ?: return
        try {
            wakes.wake(wait.orgId, session, wait.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logger.error("wake of a wait's session failed", mapOf("error" to error::class.simpleName))
        }
    }

    override suspend fun cancelled(wait: Wait) = events.cancelled(wait)
}
