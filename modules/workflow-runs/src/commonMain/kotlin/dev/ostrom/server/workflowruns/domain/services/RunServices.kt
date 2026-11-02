// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.workflowruns.domain.services

import dev.ostrom.core.api.models.NotificationStatus
import dev.ostrom.server.gates.domain.usecases.SessionGate
import dev.ostrom.server.sessions.domain.models.Session
import dev.ostrom.server.workflowruns.domain.models.RunPackage
import kotlin.uuid.Uuid

/** The organization's compiled packages (the compiler module). */
interface RunPackages {
    /**
     * Reads a package and its payload.
     *
     * @param orgId The organization.
     * @param digest The package's digest.
     *
     * @return The package, or null when it is not one of the organization's.
     */
    suspend fun find(orgId: Uuid, digest: String): RunPackage?
}

/** The organization's sessions and their gates (the sessions and gates modules). */
interface RunSessions {
    /**
     * Reads a session.
     *
     * @param orgId The organization.
     * @param id The session segment.
     *
     * @return The session, or null.
     */
    suspend fun find(orgId: Uuid, id: String): Session?

    /**
     * Lists the gates a session asked or joined.
     *
     * @param orgId The organization.
     * @param id The session segment.
     *
     * @return The gates, oldest first.
     */
    suspend fun gates(orgId: Uuid, id: String): List<SessionGate>
}

/**
 * What became of a notification handed to a channel.
 *
 * @property status Sent, queued for a later attempt, or not sent.
 * @property error Why not, when not sent.
 */
data class Delivery(val status: NotificationStatus, val error: String? = null)

/** Delivers the notifications of notify nodes: the channel's secret never leaves the server. */
interface NotificationChannels {
    /**
     * Delivers a message.
     *
     * @param orgId The organization.
     * @param channel `log:<name>`, `slack:<channel>` or `discord:<name>`.
     * @param text The message.
     *
     * @return What became of it.
     */
    suspend fun deliver(orgId: Uuid, channel: String, text: String): Delivery
}
