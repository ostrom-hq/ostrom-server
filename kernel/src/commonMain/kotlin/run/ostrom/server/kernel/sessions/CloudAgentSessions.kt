// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.sessions

import kotlin.uuid.Uuid

/**
 * The cloud sessions of an organization, as agent sessions: the agent of a cloud session runs with its launch's agent
 * token and no session id of the sessions module, so its todo, its waits and its proposals are the cloud session's
 * (TeamCode's pod session, bound to its execution credential). The runners module binds it; the sessions module's
 * [AgentSessions] and the todo fall back on it for an id no segment has. Unbound, no cloud session is found.
 */
fun interface CloudAgentSessions {
    /**
     * A cloud session, by its id.
     *
     * @param orgId The organization.
     * @param sessionId The cloud session's id.
     *
     * @return The session, its id as its one segment, open while an execution of it runs; null when the organization
     *   has no such cloud session or its owner is no member any more.
     */
    suspend fun find(orgId: Uuid, sessionId: String): AgentSession?
}
