// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.mcprelay.domain

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Storage of the relay; every method is scoped by the org. One port for the five tables of the module (servers,
 * assignments, policies, settings, journal), which are written together and confined by the same policy.
 */
@Suppress("TooManyFunctions") // One port per module's storage, as in the other modules.
interface McpRelayRepository {
    /**
     * Inserts a server.
     *
     * @return False when the org already has a server with this name.
     */
    suspend fun createServer(server: McpServer): Boolean

    /**
     * Replaces a server's definition, bumping its version.
     *
     * @return The server after the change, or null when it does not exist.
     */
    suspend fun updateServer(server: McpServer): McpServer?

    /** Deletes a server with its assignments and policies (journal rows stay). @return True when deleted. */
    suspend fun deleteServer(orgId: Uuid, id: Uuid): Boolean

    /** Reads a server by id. */
    suspend fun getServer(orgId: Uuid, id: Uuid): McpServer?

    /** Lists the org's servers by name (at most [limit]). */
    suspend fun listServers(orgId: Uuid, limit: Int): List<McpServer>

    /**
     * Replaces the assignment of a member (or of the whole org when [McpAssignment.memberId] is null).
     */
    suspend fun putAssignment(assignment: McpAssignment)

    /** Removes an assignment. @return True when one was removed. */
    suspend fun deleteAssignment(orgId: Uuid, serverId: Uuid, memberId: Uuid?): Boolean

    /** Lists a server's assignments, the org-wide one first. */
    suspend fun listAssignments(orgId: Uuid, serverId: Uuid): List<McpAssignment>

    /**
     * The servers a member reaches, each with the assignment that applies (the member's own row wins), in one query.
     *
     * @param orgId The org.
     * @param memberId The member.
     * @param limit The most servers returned.
     */
    suspend fun effectiveServers(orgId: Uuid, memberId: Uuid, limit: Int): List<EffectiveServer>

    /** The server named [name] if the member reaches it, with the assignment that applies, in one query. */
    suspend fun effectiveServer(orgId: Uuid, memberId: Uuid, name: String): EffectiveServer?

    /** Inserts an argument policy. */
    suspend fun createPolicy(policy: ArgumentPolicy)

    /** Deletes an argument policy. @return True when deleted. */
    suspend fun deletePolicy(orgId: Uuid, serverId: Uuid, id: Uuid): Boolean

    /** A server's argument policies, oldest first. */
    suspend fun listPolicies(orgId: Uuid, serverId: Uuid): List<ArgumentPolicy>

    /** The org's settings, or the defaults. */
    suspend fun settings(orgId: Uuid): McpSettings

    /** Replaces the org's settings. */
    suspend fun putSettings(orgId: Uuid, settings: McpSettings)

    /** Appends a journal row. */
    suspend fun record(call: McpCall)

    /**
     * The org's journal, newest first (by start, then id), after the [before] cursor (keyset), at most [limit], of
     * one server or member when given.
     */
    suspend fun listCalls(
        orgId: Uuid,
        before: McpCallCursor?,
        limit: Int,
        server: String? = null,
        memberId: Uuid? = null,
    ): List<McpCall>

    /** Totals per member and server since [since]; only [memberId]'s when set. */
    suspend fun usage(orgId: Uuid, since: Instant, memberId: Uuid?): List<McpUsage>

    /** Deletes journal rows older than [before]. @return How many. */
    suspend fun purgeCalls(before: Instant): Long

    /**
     * The assignments of every org's stdio servers, for the retain sweep: no definition, no secret.
     *
     * @param limit The most rows.
     *
     * @return The assignments, or null when there are more than [limit].
     */
    suspend fun hostedAssignments(limit: Int): List<HostedAssignment>?
}
