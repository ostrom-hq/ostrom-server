// SPDX-License-Identifier: Apache-2.0
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
     * Inserts a team server.
     *
     * @return False when the org already has a server with this name, a member's own included.
     */
    suspend fun createServer(server: McpServer): Boolean

    /**
     * Inserts a member's own server and assigns it to them, every tool, in one transaction; the member's servers are
     * counted under a lock, so two at once never pass [limit].
     *
     * @param server The server, with its owner.
     * @param limit The most personal servers a member keeps.
     */
    suspend fun createPersonalServer(server: McpServer, limit: Int): PersonalServerOutcome

    /**
     * Replaces a server's definition, bumping its version; only while its owner is still [McpServer.ownerMemberId]
     * (null: a team server).
     *
     * @return The server after the change, or null when it does not exist.
     */
    suspend fun updateServer(server: McpServer): McpServer?

    /**
     * Deletes a server with its assignments and policies (journal rows stay).
     *
     * @param ownerMemberId Only the server of this member, or any when null.
     *
     * @return True when deleted.
     */
    suspend fun deleteServer(orgId: Uuid, id: Uuid, ownerMemberId: Uuid? = null): Boolean

    /** Reads a server by id. */
    suspend fun getServer(orgId: Uuid, id: Uuid): McpServer?

    /** Reads a server, team or personal, by name. */
    suspend fun getServerByName(orgId: Uuid, name: String): McpServer?

    /** Lists the org's team servers by name (at most [limit]). */
    suspend fun listServers(orgId: Uuid, limit: Int): List<McpServer>

    /**
     * Lists personal servers, by owner then name (at most [limit]).
     *
     * @param ownerMemberId Only this member's, or every member's when null.
     */
    suspend fun listPersonalServers(orgId: Uuid, ownerMemberId: Uuid?, limit: Int): List<McpServer>

    /**
     * Makes a personal server a team server: its owner is cleared and keeps their assignment. The team's servers are
     * counted in the same transaction, under a lock that serializes promotions.
     *
     * @param limit The most team servers.
     *
     * @return The server after the change, or null when there is no personal server with this id.
     *
     * @throws McpServerLimitException When the team already has [limit] servers.
     */
    suspend fun promoteServer(orgId: Uuid, id: Uuid, limit: Int): McpServer?

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

    /** The org's settings, or the defaults (`memberHostedMcp` off). */
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

    /**
     * The org's stdio servers with their assignments, for the hosted processes' administration.
     *
     * @param orgId The org.
     * @param limit The most rows.
     *
     * @return Each server with one of its assignments, or null when there are more than [limit].
     */
    suspend fun orgHostedAssignments(orgId: Uuid, limit: Int): List<EffectiveServer>?

    /**
     * The org's recorded process states.
     *
     * @param orgId The org.
     * @param limit The most rows.
     *
     * @return The states, or null when there are more than [limit].
     */
    suspend fun stdioStates(orgId: Uuid, limit: Int): List<HostedProcessRecord>?

    /**
     * The recorded states of one member's processes: for each server, the member's row and the shared one.
     *
     * @param orgId The org.
     * @param serverIds The servers.
     * @param memberId The member.
     */
    suspend fun memberStdioStates(orgId: Uuid, serverIds: Collection<Uuid>, memberId: Uuid): List<HostedProcessRecord>

    /**
     * Applies the changes whose previous row is still stored (compare-and-set), and deletes the removals still as read.
     *
     * @param orgId The org.
     * @param changes The writes of the state sweep.
     * @param removals The states of processes no longer assigned.
     *
     * @return How many changes were applied.
     */
    suspend fun applyStdioStates(
        orgId: Uuid,
        changes: List<HostedProcessChange>,
        removals: List<HostedProcessRecord>,
    ): Int
}

/** How storing a member's own server ended. */
enum class PersonalServerOutcome {
    /** Stored and assigned to its owner. */
    CREATED,

    /** The organization already has a server with this name. */
    NAME_TAKEN,

    /** The member already keeps the most personal servers. */
    LIMIT,
}
