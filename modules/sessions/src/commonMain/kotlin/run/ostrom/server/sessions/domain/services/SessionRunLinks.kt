// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sessions.domain.services

import run.ostrom.server.kernel.workflows.SessionRunIds
import run.ostrom.server.sessions.domain.models.Session
import kotlin.uuid.Uuid

/**
 * The workflow run each segment works for (`Session.runId`, Ostrom's own): an agent node of a run starts a cloud
 * session, whose agent opens its segments under the launch of that cloud session, so a segment's run is its launch's.
 * A segment without a launch, or whose launch no run started, has none.
 *
 * @param runs The runs of the launches, when the workflow-runs module binds them; without it, no segment has a run.
 */
class SessionRunLinks(private val runs: SessionRunIds?) {
    /**
     * The runs of these segments, in one query for a whole page.
     *
     * @param orgId The organization of the segments.
     * @param sessions The segments.
     * @return The run id of each segment a run started, by segment id; any other is absent.
     */
    suspend fun of(orgId: Uuid, sessions: List<Session>): Map<String, String> {
        val port = runs ?: return emptyMap()
        val byLaunch = port.runsOfLaunches(orgId, sessions.mapNotNull { it.launchId }.toSet())
        return sessions.mapNotNull { session -> session.launchId?.let(byLaunch::get)?.let { session.id to it } }.toMap()
    }
}
