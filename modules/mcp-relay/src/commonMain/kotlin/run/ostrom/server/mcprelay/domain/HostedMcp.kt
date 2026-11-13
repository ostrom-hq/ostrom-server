// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.mcprelay.domain

import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.secrets.domain.Caller
import run.ostrom.server.secrets.domain.RevealSecretsUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
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
 * agent's first tools/list then finds them ready instead of waiting for a download (TeamCode's warm-up). A key is
 * warmed at most once a minute, so a client that lists often neither decrypts the secrets nor calls the host each
 * time; a warm-up that fails leaves the key free for the next listing.
 *
 * @param revealSecrets Decrypts the secret variables, as for a call.
 * @param host The MCP host.
 * @param scope Where the warm-ups run, outside the listing request.
 * @param clock When a key was last warmed.
 */
class HostedWarmup(
    private val revealSecrets: RevealSecretsUseCase,
    private val host: McpHostControl,
    private val scope: CoroutineScope,
    private val clock: Clock,
) {
    private val lock = Mutex()
    private val warmed = HashMap<String, Instant>()

    /**
     * Warms each server, one after the other, without waiting.
     *
     * @param caller The member.
     * @param servers The member's stdio servers.
     */
    fun warm(caller: Caller, servers: List<EffectiveServer>) {
        scope.launch {
            for (effective in servers) {
                val key = StdioProcessKey.of(effective.server, caller.memberId, effective.assignment.assignedAt)
                if (!claim(key)) continue
                try {
                    withTimeout(WARM_TIMEOUT) { host.warm(revealSecrets.target(caller, effective)) }
                } catch (error: CancellationException) {
                    release(key)
                    throw error
                } catch (_: Exception) {
                    // A missing secret or an unreachable host: the first operation reports it to the agent.
                    release(key)
                }
            }
        }
    }

    private suspend fun claim(key: String): Boolean = lock.withLock {
        val now = clock.now()
        if (warmed.size > MAX_WARMED_KEYS) warmed.values.removeAll { now - it >= WARM_INTERVAL }
        val free = warmed[key]?.let { now - it >= WARM_INTERVAL } ?: true
        if (free) warmed[key] = now
        free
    }

    // The warm-up lasts less than the interval, so the key is still this warm-up's own.
    private suspend fun release(key: String) = withContext(NonCancellable) {
        lock.withLock { warmed.remove(key) }
    }

    /** TeamCode's warm-up bounds. */
    companion object {
        /** A key is warmed at most once in this interval. */
        val WARM_INTERVAL: Duration = 1.minutes

        /** How long one warm-up may wait for the host. */
        val WARM_TIMEOUT: Duration = 10.seconds

        /** Past this many remembered keys, the expired ones are forgotten. */
        const val MAX_WARMED_KEYS: Int = 4_096
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
     * The key of a shared server's one process.
     *
     * @param orgId The org.
     * @param serverId The server.
     * @param version The server's version.
     */
    fun shared(orgId: Uuid, serverId: Uuid, version: Int): String = "$orgId:$serverId:$version"

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
        val shared = shared(orgId, serverId, version)
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
        val keys = repository.hostedAssignments(MAX_KEYS)?.let { keys(it) } ?: return HostedSweep.Incomplete
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
        val rosters = HashMap<Uuid, Set<Uuid>?>()
        // Past the bound the sweep sends nothing anyway: stop building keys.
        for (rows in assignments.groupBy { it.serverId }.values) {
            val first = rows.first()
            val serverKeys = if (first.sharing == McpSharing.SHARED) {
                listOf(StdioProcessKey.shared(first.orgId, first.serverId, first.version))
            } else {
                perMemberKeys(rows, rosters.getOrPut(first.orgId) { roster(first.orgId) })
            }
            if (serverKeys == null || keys.size + serverKeys.size > MAX_KEYS) return null
            keys += serverKeys
        }
        return keys
    }

    // The org's members, once per org and sweep; null without a directory or past the bound.
    private suspend fun roster(orgId: Uuid): Set<Uuid>? =
        members?.memberIds(orgId, MAX_KEYS)?.mapNotNullTo(HashSet()) { Uuid.parseOrNull(it) }

    // A member's own row wins over the org-wide one, which applies to every other member of the org. A member who
    // left the org keeps no process; without the member list, the own rows are all kept and the org-wide one cannot be
    // expanded.
    private fun perMemberKeys(rows: List<HostedAssignment>, roster: Set<Uuid>?): List<String>? {
        val first = rows.first()
        val own = rows.mapNotNull { row -> row.memberId?.takeIf { roster == null || it in roster }?.let { it to row } }
            .toMap()
        val everyone = rows.firstOrNull { it.memberId == null }
        val others = if (everyone == null) {
            emptyList()
        } else {
            (roster ?: return null).filter { it !in own }.map { everyone.key(it) }
        }
        return own.map { (memberId, row) -> row.key(memberId) } + others
    }

    private fun HostedAssignment.key(memberId: Uuid) =
        StdioProcessKey.of(orgId, serverId, version, sharing, memberId, assignedAt)

    /** The sweep's bounds. */
    companion object {
        /** About 650 KiB of hashed keys, within the host's 2 MiB request bound (TeamCode's). */
        const val MAX_KEYS: Int = 10_000
    }
}
