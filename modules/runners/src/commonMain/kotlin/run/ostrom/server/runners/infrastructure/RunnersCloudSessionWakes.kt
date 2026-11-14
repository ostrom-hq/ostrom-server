// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.kernel.sessions.CloudSessionWakes
import run.ostrom.server.runners.domain.CloudSessionWake
import run.ostrom.server.runners.domain.WakeCloudSessionUseCase
import kotlin.uuid.Uuid

/**
 * [CloudSessionWakes] on [WakeCloudSessionUseCase]. A paused session that cannot resume (its owner gone, the plan
 * closed, its hours or its cap reached) is logged with its reason, never the answer: it stays paused and the answer
 * stays on the wait, taken by its next execution whoever resumes it.
 *
 * @param wake The use case.
 * @param logger Where a wake that could not resume is reported.
 */
internal class RunnersCloudSessionWakes(
    private val wake: WakeCloudSessionUseCase,
    private val logger: Logger,
) : CloudSessionWakes {
    override suspend fun wake(orgId: Uuid, sessionId: String, waitId: Uuid): Boolean {
        val outcome = wake.invoke(orgId, sessionId, waitId)
        if (outcome in REFUSED) {
            logger.info(
                "cloud session not woken by its wait",
                mapOf("orgId" to orgId, "sessionId" to sessionId, "waitId" to waitId, "reason" to outcome.name),
            )
        }
        return outcome == CloudSessionWake.RESUMED
    }
}

/** A paused session that stays paused: worth a line. The others had nothing to wake. */
private val REFUSED = setOf(
    CloudSessionWake.OWNER_GONE,
    CloudSessionWake.CLOSED,
    CloudSessionWake.NO_HOURS,
    CloudSessionWake.AT_CAP,
)
