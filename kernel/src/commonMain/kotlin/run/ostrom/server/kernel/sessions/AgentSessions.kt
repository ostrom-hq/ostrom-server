// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.sessions

import kotlin.uuid.Uuid

/**
 * An agent session as other modules see it, whichever of its segments was named.
 *
 * @property owner The login of the person who runs it.
 * @property open Whether a segment of it is open now.
 * @property segments The ids of its segments, oldest first.
 */
data class AgentSession(val owner: String, val open: Boolean, val segments: List<String>)

/**
 * The agent sessions of an organization. The sessions module binds it; other modules ask it, such as automations
 * before a member asks a wait in a session (only its owner does). Unbound, each module keeps its own fail-closed
 * default.
 */
interface AgentSessions {
    /**
     * The session a segment belongs to.
     *
     * @param orgId The organization.
     * @param sessionId Any segment of the session.
     *
     * @return The session, or null when the organization has none with this id.
     */
    suspend fun find(orgId: Uuid, sessionId: String): AgentSession?
}

/**
 * Told when an agent session ends for good (its launcher or its agent ended it, not a segment a resume replaced nor a
 * launcher lost while the machine slept, which reopens one), so that what waits on it ends with it. Called at least
 * once, again when the same end is sent again, so it is idempotent.
 */
fun interface SessionEndListener {
    /**
     * The session ended: none of its segments is open.
     *
     * @param orgId The organization.
     * @param segments The ids of its segments.
     */
    suspend fun ended(orgId: Uuid, segments: List<String>)
}
