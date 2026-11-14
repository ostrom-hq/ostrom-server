// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.domain

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Which replica holds each session's runner socket, shared by every replica: a member attached on another replica is
 * relayed to it (TC-REP-1). A replica holds a session for [LEASE] past its last renewal, so one that died frees it.
 */
interface TerminalHosts {
    /**
     * Records this replica as the session's host, unless a newer execution is held elsewhere.
     *
     * @param orgId The session's organization.
     * @param sessionId The session.
     * @param epoch The runner's execution.
     */
    suspend fun hold(orgId: Uuid, sessionId: Uuid, epoch: Long)

    /**
     * Extends this replica's hold by [LEASE] from now, or writes it again when it was lost; nothing when another
     * replica holds the session for the same or a newer execution.
     *
     * @param orgId The session's organization.
     * @param sessionId The session.
     * @param epoch The runner's execution.
     */
    suspend fun renew(orgId: Uuid, sessionId: Uuid, epoch: Long)

    /**
     * Gives the session back; nothing when another holds it.
     *
     * @param orgId The session's organization.
     * @param sessionId The session.
     * @param epoch The runner's execution.
     */
    suspend fun release(orgId: Uuid, sessionId: Uuid, epoch: Long)

    /**
     * The replica holding the session's runner socket now.
     *
     * @param orgId The session's organization.
     * @param sessionId The session.
     *
     * @return Its URL, or null when no live hold names one.
     */
    suspend fun host(orgId: Uuid, sessionId: Uuid): String?

    /** Timing. */
    companion object {
        /** A hold not renewed (its replica died) ends after this. */
        val LEASE: Duration = 90.seconds

        /** How often a replica renews the holds of the runner sockets it has open. */
        val RENEWAL: Duration = 30.seconds
    }
}
