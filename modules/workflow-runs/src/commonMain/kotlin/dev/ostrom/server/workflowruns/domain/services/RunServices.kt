// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.workflowruns.domain.services

import dev.ostrom.core.api.models.NotificationStatus
import dev.ostrom.server.gates.domain.usecases.SessionGate
import dev.ostrom.server.sessions.domain.models.Session
import dev.ostrom.server.workflowruns.domain.models.RunPackage
import dev.ostrom.server.workflowruns.domain.models.RunWorkflow
import kotlin.time.Instant
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

    /**
     * Whether a session that started at [startedAt] may report under [pack] (AUDIT-SECU-RUNNER-2 R3b): its
     * repository's pinned package, or one that no other usable package of the repository replaced before the session
     * started. A package published while the session runs never refuses the session's own.
     *
     * @param orgId The organization.
     * @param pack The package a report names.
     * @param startedAt When the session started.
     *
     * @return True when the session may report under it.
     */
    suspend fun current(orgId: Uuid, pack: RunPackage, startedAt: Instant): Boolean
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

/** The launches of the sessions (the launches module): which cloud session opened one, which repository it names. */
interface RunLaunches {
    /**
     * Reads which cloud session opened a launch.
     *
     * @param orgId The organization.
     * @param launchId The launch.
     *
     * @return The cloud session whose runner opened it, or null for a launch of the CLI or an unknown one.
     */
    suspend fun cloudSession(orgId: Uuid, launchId: String): Uuid?

    /**
     * Reads the repository a launch names: the CLI's `repo`, or the repository of the cloud session that opened it.
     *
     * @param orgId The organization.
     * @param launchId The launch.
     *
     * @return The repository, or null when the launch names none or is unknown.
     */
    suspend fun repo(orgId: Uuid, launchId: String): String?
}

/** The organization's workflows (the compiler module), as a run starts them. */
interface RunWorkflows {
    /**
     * Reads a workflow.
     *
     * @param orgId The organization.
     * @param id The workflow.
     *
     * @return The workflow, or null when the organization has none of that id.
     */
    suspend fun find(orgId: Uuid, id: String): RunWorkflow?
}
