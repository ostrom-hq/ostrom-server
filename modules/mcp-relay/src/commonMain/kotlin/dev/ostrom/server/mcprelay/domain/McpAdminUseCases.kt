package dev.ostrom.server.mcprelay.domain

import dev.ostrom.server.secrets.domain.Caller
import dev.ostrom.server.secrets.domain.RevealSecretsUseCase
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * What declaring or changing a server needs.
 *
 * @property name The endpoint name.
 * @property transport How it is reached.
 * @property url The HTTPS endpoint (HTTP).
 * @property credentialSecretId The shared credential (HTTP, shared).
 * @property credentialHeader The header carrying it; null for Bearer.
 * @property stdio The command (stdio).
 * @property sharing Shared, or each member brings their own credential.
 */
data class McpServerPayload(
    val name: String,
    val transport: McpTransport,
    val url: String? = null,
    val credentialSecretId: Uuid? = null,
    val credentialHeader: String? = null,
    val stdio: StdioSpec? = null,
    val sharing: McpSharing = McpSharing.SHARED,
)

/** Whether the server can run stdio servers (an MCP host is configured). */
fun interface McpHostAvailability {
    /** True when an MCP host is configured. */
    fun isConfigured(): Boolean
}

/** Declares a server (admins). */
interface CreateMcpServerUseCase {
    /**
     * Declares it.
     *
     * @throws InvalidMcpServerException, McpServerNameTakenException, McpSecretNotFoundException, McpHostDisabledException
     */
    suspend operator fun invoke(caller: Caller, payload: McpServerPayload): McpServer
}

/** Changes a server (admins); its version is bumped. */
interface UpdateMcpServerUseCase {
    /** Changes it. @throws McpServerNotFoundException When the org has no such server. */
    suspend operator fun invoke(caller: Caller, id: Uuid, payload: McpServerPayload): McpServer
}

/** Deletes a server with its assignments and policies (admins). */
interface DeleteMcpServerUseCase {
    /** Deletes it. @throws McpServerNotFoundException When the org has no such server. */
    suspend operator fun invoke(caller: Caller, id: Uuid)
}

/** Lists the org's servers (admins). */
interface ListMcpServersUseCase {
    /** Lists them, by name. */
    suspend operator fun invoke(caller: Caller): List<McpServer>
}

/**
 * [CreateMcpServerUseCase], [UpdateMcpServerUseCase], [DeleteMcpServerUseCase] and [ListMcpServersUseCase] share
 * their checks here.
 *
 * @param repository The storage.
 * @param revealSecrets Checks that a named credential is an org secret.
 * @param host Whether stdio servers can run.
 * @param clock The time.
 */
class McpServerAdministration(
    private val repository: McpRelayRepository,
    private val revealSecrets: RevealSecretsUseCase,
    private val host: McpHostAvailability,
    private val clock: Clock,
) {
    /** See [CreateMcpServerUseCase]. */
    suspend fun create(caller: Caller, payload: McpServerPayload): McpServer {
        caller.requireAdmin()
        if (repository.listServers(caller.orgId, McpRules.MAX_SERVERS).size >= McpRules.MAX_SERVERS) {
            throw InvalidMcpServerException("An organization has at most ${McpRules.MAX_SERVERS} MCP servers.")
        }
        val server = payload.toServer(Uuid.random(), caller.orgId, 1, clock.now())
        check(caller, server)
        if (!repository.createServer(server)) throw McpServerNameTakenException()
        return server
    }

    /** See [UpdateMcpServerUseCase]. */
    suspend fun update(caller: Caller, id: Uuid, payload: McpServerPayload): McpServer {
        caller.requireAdmin()
        val current = repository.getServer(caller.orgId, id) ?: throw McpServerNotFoundException()
        val next = payload.toServer(id, caller.orgId, current.version + 1, current.createdAt)
        check(caller, next)
        return repository.updateServer(next) ?: throw McpServerNotFoundException()
    }

    /** See [DeleteMcpServerUseCase]. */
    suspend fun delete(caller: Caller, id: Uuid) {
        caller.requireAdmin()
        if (!repository.deleteServer(caller.orgId, id)) throw McpServerNotFoundException()
    }

    /** See [ListMcpServersUseCase]. */
    suspend fun list(caller: Caller): List<McpServer> {
        caller.requireAdmin()
        return repository.listServers(caller.orgId, McpRules.MAX_SERVERS)
    }

    private suspend fun check(caller: Caller, server: McpServer) {
        McpRules.requireServer(server)
        if (server.transport == McpTransport.STDIO && !host.isConfigured()) throw McpHostDisabledException()
        if (server.sharing == McpSharing.PER_MEMBER && server.credentialSecretId != null) {
            throw InvalidMcpServerException("A per-member server takes each member's credential at assignment, not a shared one.")
        }
        val referenced = listOfNotNull(server.credentialSecretId) + server.stdio?.secretEnv?.values.orEmpty()
        requireSecrets(revealSecrets, caller, referenced)
    }

    private fun McpServerPayload.toServer(id: Uuid, orgId: Uuid, version: Int, createdAt: Instant) = McpServer(
        id = id,
        orgId = orgId,
        name = name,
        transport = transport,
        url = url,
        credentialSecretId = credentialSecretId,
        credentialHeader = credentialHeader,
        stdio = stdio,
        sharing = sharing,
        version = version,
        createdAt = createdAt,
    )
}

/** [CreateMcpServerUseCase] through [McpServerAdministration]. */
class CreateMcpServerUseCaseImpl(private val administration: McpServerAdministration) : CreateMcpServerUseCase {
    override suspend fun invoke(caller: Caller, payload: McpServerPayload): McpServer = administration.create(caller, payload)
}

/** [UpdateMcpServerUseCase] through [McpServerAdministration]. */
class UpdateMcpServerUseCaseImpl(private val administration: McpServerAdministration) : UpdateMcpServerUseCase {
    override suspend fun invoke(caller: Caller, id: Uuid, payload: McpServerPayload): McpServer = administration.update(caller, id, payload)
}

/** [DeleteMcpServerUseCase] through [McpServerAdministration]. */
class DeleteMcpServerUseCaseImpl(private val administration: McpServerAdministration) : DeleteMcpServerUseCase {
    override suspend fun invoke(caller: Caller, id: Uuid) = administration.delete(caller, id)
}

/** [ListMcpServersUseCase] through [McpServerAdministration]. */
class ListMcpServersUseCaseImpl(private val administration: McpServerAdministration) : ListMcpServersUseCase {
    override suspend fun invoke(caller: Caller): List<McpServer> = administration.list(caller)
}

/** Assigns a server, and the tools allowed, to a member or to the whole org (admins). */
interface AssignMcpServerUseCase {
    /**
     * Assigns it, replacing a previous assignment of the same member.
     *
     * @param memberId The member, or null for every member.
     * @param policy The tools allowed.
     * @param credentialSecretId The member's own credential, for a per-member server.
     */
    suspend operator fun invoke(caller: Caller, serverId: Uuid, memberId: Uuid?, policy: ToolPolicy, credentialSecretId: Uuid?): McpAssignment
}

/**
 * [AssignMcpServerUseCase] on the repository.
 *
 * @param repository The storage.
 * @param revealSecrets Checks the member's credential is an org secret.
 * @param clock The assignment time.
 */
class AssignMcpServerUseCaseImpl(
    private val repository: McpRelayRepository,
    private val revealSecrets: RevealSecretsUseCase,
    private val clock: Clock,
) : AssignMcpServerUseCase {
    override suspend fun invoke(caller: Caller, serverId: Uuid, memberId: Uuid?, policy: ToolPolicy, credentialSecretId: Uuid?): McpAssignment {
        caller.requireAdmin()
        val server = repository.getServer(caller.orgId, serverId) ?: throw McpServerNotFoundException()
        McpRules.requirePolicy(policy)
        if (credentialSecretId != null && (server.sharing != McpSharing.PER_MEMBER || memberId == null)) {
            throw InvalidMcpServerException("Only a member's assignment to a per-member server carries a credential.")
        }
        requireSecrets(revealSecrets, caller, listOfNotNull(credentialSecretId))
        val assignment = McpAssignment(caller.orgId, serverId, memberId, policy, credentialSecretId, clock.now())
        repository.putAssignment(assignment)
        return assignment
    }
}

/** Removes an assignment (admins). */
interface UnassignMcpServerUseCase {
    /** Removes it. @throws McpServerNotFoundException When there is none. */
    suspend operator fun invoke(caller: Caller, serverId: Uuid, memberId: Uuid?)
}

/** [UnassignMcpServerUseCase] on the repository. */
class UnassignMcpServerUseCaseImpl(private val repository: McpRelayRepository) : UnassignMcpServerUseCase {
    override suspend fun invoke(caller: Caller, serverId: Uuid, memberId: Uuid?) {
        caller.requireAdmin()
        if (!repository.deleteAssignment(caller.orgId, serverId, memberId)) throw McpServerNotFoundException()
    }
}

/** Lists a server's assignments (admins). */
interface ListMcpAssignmentsUseCase {
    /** Lists them. @throws McpServerNotFoundException When the org has no such server. */
    suspend operator fun invoke(caller: Caller, serverId: Uuid): List<McpAssignment>
}

/** [ListMcpAssignmentsUseCase] on the repository. */
class ListMcpAssignmentsUseCaseImpl(private val repository: McpRelayRepository) : ListMcpAssignmentsUseCase {
    override suspend fun invoke(caller: Caller, serverId: Uuid): List<McpAssignment> {
        caller.requireAdmin()
        repository.getServer(caller.orgId, serverId) ?: throw McpServerNotFoundException()
        return repository.listAssignments(caller.orgId, serverId)
    }
}

/**
 * What an argument policy needs.
 *
 * @property tool The tool, or `*`.
 * @property conditions The tests, all required.
 * @property effect Deny, or require approval.
 * @property message Why.
 */
data class ArgumentPolicyPayload(
    val tool: String,
    val conditions: List<ArgumentCondition>,
    val effect: PolicyEffect,
    val message: String,
)

/** Adds an argument policy to a server (admins). */
interface CreateArgumentPolicyUseCase {
    /** Adds it. @throws InvalidMcpServerException When it is malformed or a regular expression does not compile. */
    suspend operator fun invoke(caller: Caller, serverId: Uuid, payload: ArgumentPolicyPayload): ArgumentPolicy
}

/** [CreateArgumentPolicyUseCase] on the repository. */
class CreateArgumentPolicyUseCaseImpl(
    private val repository: McpRelayRepository,
    private val clock: Clock,
) : CreateArgumentPolicyUseCase {
    override suspend fun invoke(caller: Caller, serverId: Uuid, payload: ArgumentPolicyPayload): ArgumentPolicy {
        caller.requireAdmin()
        repository.getServer(caller.orgId, serverId) ?: throw McpServerNotFoundException()
        if (repository.listPolicies(caller.orgId, serverId).size >= McpRules.MAX_POLICIES) {
            throw InvalidMcpServerException("A server has at most ${McpRules.MAX_POLICIES} policies.")
        }
        val policy = ArgumentPolicy(Uuid.random(), caller.orgId, serverId, payload.tool, payload.conditions, payload.effect, payload.message, clock.now())
        McpRules.requireArgumentPolicy(policy)
        repository.createPolicy(policy)
        return policy
    }
}

/** Removes an argument policy (admins). */
interface DeleteArgumentPolicyUseCase {
    /** Removes it. @throws McpServerNotFoundException When there is none. */
    suspend operator fun invoke(caller: Caller, serverId: Uuid, policyId: Uuid)
}

/** [DeleteArgumentPolicyUseCase] on the repository. */
class DeleteArgumentPolicyUseCaseImpl(private val repository: McpRelayRepository) : DeleteArgumentPolicyUseCase {
    override suspend fun invoke(caller: Caller, serverId: Uuid, policyId: Uuid) {
        caller.requireAdmin()
        if (!repository.deletePolicy(caller.orgId, serverId, policyId)) throw McpServerNotFoundException()
    }
}

/** Lists a server's argument policies (admins). */
interface ListArgumentPoliciesUseCase {
    /** Lists them. */
    suspend operator fun invoke(caller: Caller, serverId: Uuid): List<ArgumentPolicy>
}

/** [ListArgumentPoliciesUseCase] on the repository. */
class ListArgumentPoliciesUseCaseImpl(private val repository: McpRelayRepository) : ListArgumentPoliciesUseCase {
    override suspend fun invoke(caller: Caller, serverId: Uuid): List<ArgumentPolicy> {
        caller.requireAdmin()
        repository.getServer(caller.orgId, serverId) ?: throw McpServerNotFoundException()
        return repository.listPolicies(caller.orgId, serverId)
    }
}

/** Reads the org's relay settings (any member: the CLI needs the prefix). */
interface GetMcpSettingsUseCase {
    /** Reads them. */
    suspend operator fun invoke(caller: Caller): McpSettings
}

/** [GetMcpSettingsUseCase] on the repository. */
class GetMcpSettingsUseCaseImpl(private val repository: McpRelayRepository) : GetMcpSettingsUseCase {
    override suspend fun invoke(caller: Caller): McpSettings = repository.settings(caller.orgId)
}

/** Changes the org's relay settings (admins). */
interface PutMcpSettingsUseCase {
    /** Changes them. @throws InvalidMcpServerException When the prefix is malformed. */
    suspend operator fun invoke(caller: Caller, settings: McpSettings): McpSettings
}

/** [PutMcpSettingsUseCase] on the repository. */
class PutMcpSettingsUseCaseImpl(private val repository: McpRelayRepository) : PutMcpSettingsUseCase {
    override suspend fun invoke(caller: Caller, settings: McpSettings): McpSettings {
        caller.requireAdmin()
        McpRules.requirePrefix(settings.agentPrefix)
        repository.putSettings(caller.orgId, settings)
        return settings
    }
}

/** Reads the journal (admins), newest first. */
interface ListMcpCallsUseCase {
    /** Reads a page. */
    suspend operator fun invoke(caller: Caller, before: Instant?, limit: Int): List<McpCall>
}

/** [ListMcpCallsUseCase] on the repository. */
class ListMcpCallsUseCaseImpl(private val repository: McpRelayRepository) : ListMcpCallsUseCase {
    override suspend fun invoke(caller: Caller, before: Instant?, limit: Int): List<McpCall> {
        caller.requireAdmin()
        return repository.listCalls(caller.orgId, before, limit.coerceIn(1, 200))
    }
}

/** Usage totals per member and server: admins see everyone, members themselves. */
interface McpUsageUseCase {
    /** Totals over the last [days] days (1 to 30). */
    suspend operator fun invoke(caller: Caller, days: Int): List<McpUsage>
}

/** [McpUsageUseCase] on the repository. */
class McpUsageUseCaseImpl(private val repository: McpRelayRepository, private val clock: Clock) : McpUsageUseCase {
    override suspend fun invoke(caller: Caller, days: Int): List<McpUsage> =
        repository.usage(caller.orgId, clock.now() - days.coerceIn(1, 30).days, if (caller.role.isAdmin) null else caller.memberId)
}

private suspend fun requireSecrets(revealSecrets: RevealSecretsUseCase, caller: Caller, ids: List<Uuid>) {
    if (ids.isEmpty()) return
    val found = revealSecrets(caller.orgId, ids).keys
    if (!found.containsAll(ids)) throw McpSecretNotFoundException()
}
