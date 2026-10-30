// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.sessions.domain.services

import kotlin.uuid.Uuid

/**
 * What a session cost, as the server measured it (the model gateway's usage), never what the agent says: the figure
 * is signed into the session's receipt (SECURITY-AUDIT S2-48). The usage module binds it over [NoSessionCosts].
 */
interface SessionCosts {
    /**
     * @param orgId The organization.
     * @param sessionId The segment.
     * @return Its cost in US dollars, or null when nothing was measured.
     */
    suspend fun costOf(orgId: Uuid, sessionId: String): Double?
}

/** Without the usage module, no cost is known: the receipt says none rather than the agent's. */
class NoSessionCosts : SessionCosts {
    override suspend fun costOf(orgId: Uuid, sessionId: String): Double? = null
}
