// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners.domain

import dev.ostrom.server.kernel.observability.OstromMetrics

/**
 * Counts the cloud sessions created ([OstromMetrics.sessionStarted], `where = cloud`) around [create], which decides
 * alone: a refused session throws before it is counted, a resume is not a new session.
 *
 * @param create Creates the session.
 * @param metrics The server's instruments.
 */
class MeteredCreateCloudSession(
    private val create: CreateCloudSessionUseCase,
    private val metrics: OstromMetrics,
) : CreateCloudSessionUseCase {
    override suspend fun invoke(caller: Caller, payload: CreateCloudSessionPayload): CloudSession =
        create(caller, payload).also { metrics.sessionStarted(OstromMetrics.agentOf(it.agent.wire), "cloud") }
}
