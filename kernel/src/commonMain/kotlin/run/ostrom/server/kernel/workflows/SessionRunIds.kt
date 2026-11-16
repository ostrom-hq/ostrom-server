// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.workflows

import kotlin.uuid.Uuid

/**
 * The workflow runs that started sessions, so that a session names its run (`runId`) without its module depending on
 * the workflow-runs module. An agent node of a run starts a cloud session (the runners module's); the agent in it runs
 * with the launch's agent token, so the segments it opens (the sessions module's) carry that launch, whose cloud
 * session is the run's. The workflow-runs module binds it; unbound, no session has a run.
 */
interface SessionRunIds {
    /**
     * The runs of these cloud sessions, in one query.
     *
     * @param orgId The organization.
     * @param sessionIds The cloud sessions' ids.
     *
     * @return The id of the run whose latest visit started each cloud session of [orgId] a run started; any other is
     *   absent.
     */
    suspend fun runsOf(orgId: Uuid, sessionIds: Collection<Uuid>): Map<Uuid, String>

    /**
     * The runs of the cloud sessions these launches ran in, in one query: the runs of the segments they opened.
     *
     * @param orgId The organization.
     * @param launchIds The launches' ids.
     *
     * @return The id of the run of each launch of [orgId] that ran in a cloud session a run started; any other is
     *   absent.
     */
    suspend fun runsOfLaunches(orgId: Uuid, launchIds: Collection<String>): Map<String, String>
}
