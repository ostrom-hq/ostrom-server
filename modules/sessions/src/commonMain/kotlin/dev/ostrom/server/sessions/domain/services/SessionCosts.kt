// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.sessions.domain.services

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * What a session cost, as the server measured it (the model gateway's usage), never what the agent says: the figure
 * is signed into the session's receipt (SECURITY-AUDIT S2-48). The usage module binds it; without it,
 * [NoSessionCosts].
 */
interface SessionCosts {
    /**
     * @param orgId The organization.
     * @param launchId The segment's launch, whose agent token the gateway metered; null when it has none.
     * @param from When the segment started.
     * @param to When it ends.
     * @return Its cost in US dollars, or null when nothing was measured.
     */
    suspend fun costOf(orgId: Uuid, launchId: String?, from: Instant, to: Instant): Double?
}

/** Without the usage module, no cost is known: the receipt says none rather than the agent's. */
class NoSessionCosts : SessionCosts {
    override suspend fun costOf(orgId: Uuid, launchId: String?, from: Instant, to: Instant): Double? = null
}
