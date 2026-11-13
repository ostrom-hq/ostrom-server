// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.mcprelay.domain

import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.secrets.domain.Caller
import run.ostrom.server.secrets.domain.RevealSecretsUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The MCP host's operations beside the relayed list and call (docs/mcp-host.md, host protocol v1): what keeps its
 * processes in step with the assignments. It holds nothing the relay depends on: a lost process starts again.
 */
interface McpHostControl {
    /**
     * Starts the process of a target in the background, so the agent's first tools/list finds it ready.
     *
     * @param target The server, its secret variables and its process key.
     */
    suspend fun warm(target: UpstreamTarget)

    /**
     * Stops every process whose key is not listed.
     *
     * @param keys The process keys still assigned, as [UpstreamTarget.processKey] gives them.
     *
     * @return How many processes the host stopped.
     */
    suspend fun retain(keys: Collection<String>): Int
}

/**
 * Starts a member's stdio processes on the MCP host in the background, when the member lists its servers: the
 * agent's first tools/list then finds them ready instead of waiting for a download (TeamCode's warm-up).
 *
 * @param revealSecrets Decrypts the secret variables, as for a call.
 * @param host The MCP host.
 * @param scope Where the warm-ups run, outside the listing request.
 */
class HostedWarmup(
    private val revealSecrets: RevealSecretsUseCase,
    private val host: McpHostControl,
    private val scope: CoroutineScope,
) {
    /**
     * Warms each server, one after the other, without waiting.
     *
     * @param caller The member.
     * @param servers The member's stdio servers.
     */
    fun warm(caller: Caller, servers: List<EffectiveServer>) {
        scope.launch {
            for (effective in servers) {
                try {
                    host.warm(revealSecrets.target(caller, effective))
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // A missing secret or an unreachable host: the first operation reports it to the agent.
                    Unit
                }
            }
        }
    }
}

/** The relay key of a stdio process: `org:server:version`, plus `:member:assignedAt` for a per-member server. */
object StdioProcessKey {
    /**
     * The key of one member's process. No secret is part of it: the host restarts a process whose definition, secret
     * values included, changed under the same key, so a rotation still starts a new process, and the retain sweep
     * computes every key without decrypting anything.
     *
     * @param server The server.
     * @param memberId The member reaching it.
     * @param assignedAt When the assignment that applies was made.
     */
    fun of(server: McpServer, memberId: Uuid, assignedAt: Instant): String =
        of(server.orgId, server.id, server.version, server.sharing, memberId, assignedAt)

    /**
     * The same from the stored columns.
     *
     * @param orgId The org.
     * @param serverId The server.
     * @param version The server's version.
     * @param sharing One process per server, or per member.
     * @param memberId The member.
     * @param assignedAt When the assignment that applies was made.
     */
    @Suppress("LongParameterList") // The columns of the key, as TeamCode's StdioProcessKey takes them.
    fun of(
        orgId: Uuid,
        serverId: Uuid,
        version: Int,
        sharing: McpSharing,
        memberId: Uuid,
        assignedAt: Instant,
    ): String {
        val shared = "$orgId:$serverId:$version"
        return when (sharing) {
            McpSharing.SHARED -> shared
            McpSharing.PER_MEMBER -> "$shared:$memberId:$assignedAt"
        }
    }
}

/**
 * One assignment of a stdio server, as the retain sweep reads it: enough to compute process keys, nothing secret.
 *
 * @property orgId The org.
 * @property serverId The server.
 * @property version The server's version.
 * @property sharing One process per server, or per member.
 * @property memberId The member, or null for every member of the org.
 * @property assignedAt When it was assigned.
 */
data class HostedAssignment(
    val orgId: Uuid,
    val serverId: Uuid,
    val version: Int,
    val sharing: McpSharing,
    val memberId: Uuid?,
    val assignedAt: Instant,
)

/** What one retain sweep did. */
sealed interface HostedSweep {
    /**
     * The host was told the keys.
     *
     * @property keys How many keys are still assigned.
     * @property stopped How many processes the host stopped.
     */
    data class Done(val keys: Int, val stopped: Int) : HostedSweep

    /** The assignments could not be listed completely: nothing is sent, so no assigned process is stopped. */
    data object Incomplete : HostedSweep

    /** The host did not answer, or refused the relay. */
    data object HostUnavailable : HostedSweep
}

/**
 * The retain sweep, run each minute (TeamCode's `RetainHostedMcpProcessesUseCase`): it tells the host which processes
 * are still assigned, so the process of a deleted server, a new version, a removed assignment or a removed member
 * stops within a minute (the relay refuses their calls at once anyway; this frees the process and the secrets it
 * holds).
 */
interface RetainHostedMcpProcessesUseCase {
    /** Runs one sweep. */
    suspend operator fun invoke(): HostedSweep
}

/**
 * [RetainHostedMcpProcessesUseCase] on the repository and the org's members.
 *
 * @param repository The relay's servers and assignments.
 * @param members Who belongs to an org, for a per-member server assigned to the whole org; null when no module binds
 * it, and such an assignment then makes the sweep incomplete.
 * @param host The MCP host.
 */
class RetainHostedMcpProcessesUseCaseImpl(
    private val repository: McpRelayRepository,
    private val members: MemberDirectory?,
    private val host: McpHostControl,
) : RetainHostedMcpProcessesUseCase {
    override suspend fun invoke(): HostedSweep {
        // A truncated list would stop processes that are still assigned: rather keep everything until it fits.
        val keys = repository.hostedAssignments(MAX_KEYS)?.let { keys(it) }?.takeIf { it.size <= MAX_KEYS }
            ?: return HostedSweep.Incomplete
        return try {
            HostedSweep.Done(keys.size, host.retain(keys))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            HostedSweep.HostUnavailable
        }
    }

    private suspend fun keys(assignments: List<HostedAssignment>): Set<String>? {
        val keys = HashSet<String>()
        for (rows in assignments.groupBy { it.serverId }.values) {
            val first = rows.first()
            keys += if (first.sharing == McpSharing.SHARED) {
                listOf(first.key(first.orgId, first.assignedAt))
            } else {
                perMemberKeys(rows) ?: return null
            }
        }
        return keys
    }

    // A member's own row wins over the org-wide one, which applies to every other member of the org.
    private suspend fun perMemberKeys(rows: List<HostedAssignment>): List<String>? {
        val first = rows.first()
        val own = rows.mapNotNull { row -> row.memberId?.let { it to row.assignedAt } }.toMap()
        val everyone = rows.firstOrNull { it.memberId == null }
        val others = if (everyone == null) {
            emptyList()
        } else {
            val ids = members?.memberIds(first.orgId, MAX_KEYS) ?: return null
            ids.mapNotNull { Uuid.parseOrNull(it) }.filter { it !in own }.map { first.key(it, everyone.assignedAt) }
        }
        return own.map { (memberId, assignedAt) -> first.key(memberId, assignedAt) } + others
    }

    private fun HostedAssignment.key(memberId: Uuid, assignedAt: Instant) =
        StdioProcessKey.of(orgId, serverId, version, sharing, memberId, assignedAt)

    /** The sweep's bounds. */
    companion object {
        /** About 650 KiB of hashed keys, within the host's 2 MiB request bound (TeamCode's). */
        const val MAX_KEYS: Int = 10_000
    }
}
