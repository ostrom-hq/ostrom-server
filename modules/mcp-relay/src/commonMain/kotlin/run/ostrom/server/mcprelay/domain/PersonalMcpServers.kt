// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.mcprelay.domain

import run.ostrom.server.secrets.domain.Caller
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * A member's own MCP servers (TeamCode #269, docs/variants.md "Personal variants"): relayed to their owner alone, in
 * the organization's one name space; an administrator reviews, promotes or deletes them, never changes or assigns
 * them.
 */
interface PersonalMcpServersUseCase {
    /** The caller's own servers, by name. */
    suspend fun list(caller: Caller): List<McpServer>

    /**
     * Declares one, assigned to the caller with every tool in the same transaction. A remote server follows a team
     * server's rules; a hosted command needs the team's `memberHostedMcp`, and its process is always the caller's own.
     *
     * @throws McpHostedNotAllowedException, McpServerNameTakenException, McpServerLimitException,
     *   InvalidMcpServerException, McpSecretNotFoundException
     */
    suspend fun create(caller: Caller, payload: McpServerPayload): McpServer

    /** Changes one of the caller's own, with the rules of [create]. @throws McpServerNotFoundException */
    suspend fun update(caller: Caller, name: String, payload: McpServerPayload): McpServer

    /** Deletes one of the caller's own, with its assignment. @throws McpServerNotFoundException */
    suspend fun delete(caller: Caller, name: String): McpServer

    /** Every member's own servers, by owner then name, for an administrator's review (the first 500). */
    suspend fun review(caller: Caller): List<McpServer>

    /**
     * Makes a member's own server a team server (administrators): its owner keeps their assignment. A team server is
     * returned as it is.
     *
     * @throws McpServerNotFoundException, McpServerLimitException
     */
    suspend fun promote(caller: Caller, name: String): McpServer
}

/**
 * [PersonalMcpServersUseCase] on the repository, with a team server's checks ([McpServerAdministration.check]).
 *
 * @param repository The storage.
 * @param administration The checks every server passes.
 * @param clock The time.
 */
class PersonalMcpServersUseCaseImpl(
    private val repository: McpRelayRepository,
    private val administration: McpServerAdministration,
    private val clock: Clock,
) : PersonalMcpServersUseCase {
    override suspend fun list(caller: Caller): List<McpServer> =
        repository.listPersonalServers(caller.orgId, caller.memberId, McpRules.MAX_PERSONAL_SERVERS)

    override suspend fun create(caller: Caller, payload: McpServerPayload): McpServer {
        val server = shape(caller, payload).toServer(Uuid.random(), caller.orgId, 1, clock.now(), caller.memberId)
        administration.check(caller, server)
        return when (repository.createPersonalServer(server, McpRules.MAX_PERSONAL_SERVERS)) {
            PersonalServerOutcome.CREATED -> server
            PersonalServerOutcome.NAME_TAKEN -> throw McpServerNameTakenException()
            PersonalServerOutcome.LIMIT -> throw McpServerLimitException(
                "A member keeps at most ${McpRules.MAX_PERSONAL_SERVERS} MCP servers of their own.",
            )
        }
    }

    override suspend fun update(caller: Caller, name: String, payload: McpServerPayload): McpServer {
        val current = own(caller, name)
        if (payload.name != name) {
            throw InvalidMcpServerException("The name of the body differs from the name of the path.")
        }
        val next = shape(caller, payload)
            .toServer(current.id, caller.orgId, current.version + 1, current.createdAt, caller.memberId)
        administration.check(caller, next)
        return repository.updateServer(next) ?: throw McpServerNotFoundException()
    }

    override suspend fun delete(caller: Caller, name: String): McpServer {
        val current = own(caller, name)
        if (!repository.deleteServer(caller.orgId, current.id, caller.memberId)) throw McpServerNotFoundException()
        return current
    }

    override suspend fun review(caller: Caller): List<McpServer> {
        caller.requireAdmin()
        return repository.listPersonalServers(caller.orgId, null, McpRules.MAX_PERSONAL_LISTED)
    }

    override suspend fun promote(caller: Caller, name: String): McpServer {
        caller.requireAdmin()
        val server = repository.getServerByName(caller.orgId, name) ?: throw McpServerNotFoundException()
        if (!server.personal) return server
        // Promoted by someone else meanwhile: the team server it now is.
        return repository.promoteServer(caller.orgId, server.id, McpRules.MAX_SERVERS)
            ?: repository.getServer(caller.orgId, server.id)
            ?: throw McpServerNotFoundException()
    }

    /** Another member's server, or a team one, is not found: the caller changes only their own. */
    private suspend fun own(caller: Caller, name: String): McpServer {
        val server = repository.getServerByName(caller.orgId, name)
        if (server == null || server.ownerMemberId != caller.memberId) throw McpServerNotFoundException()
        return server
    }

    /**
     * A hosted command runs package code on the MCP host: refused unless the team allows it, and then always the
     * member's own process. A remote server has one credential, the member's.
     */
    private suspend fun shape(caller: Caller, payload: McpServerPayload): McpServerPayload =
        if (payload.transport == McpTransport.STDIO) {
            if (!repository.settings(caller.orgId).memberHostedMcp) throw McpHostedNotAllowedException()
            payload.copy(sharing = McpSharing.PER_MEMBER)
        } else {
            payload.copy(sharing = McpSharing.SHARED)
        }
}
