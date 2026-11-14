// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.mcprelay.domain

import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.secrets.domain.Caller
import kotlinx.coroutines.CancellationException
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The states of a hosted process, as the MCP host reports them (docs/mcp-host.md); stored and shown as is. */
object HostedProcessStates {
    /** Nothing runs. */
    const val IDLE: String = "idle"

    /** A start is in progress. */
    const val STARTING: String = "starting"

    /** The process serves. */
    const val READY: String = "ready"

    /** Its last start failed. */
    const val FAILED: String = "failed"

    /** It failed too often and waits. */
    const val BACKOFF: String = "backoff"

    /** A state a newer host reports, never stored as is. */
    const val UNKNOWN: String = "unknown"

    private val KNOWN = setOf(IDLE, STARTING, READY, FAILED, BACKOFF)

    /**
     * The state when it is one of the host's, else [UNKNOWN].
     *
     * @param state What the host said.
     */
    fun known(state: String): String = if (state in KNOWN) state else UNKNOWN

    /**
     * Whether the process cannot serve: its last start failed, or it waits after too many failures.
     *
     * @param state A state.
     */
    fun failing(state: String): Boolean = state == FAILED || state == BACKOFF
}

/**
 * What the host says about one process (`GET /v1/status`); its stderr is scrubbed by the host, for administrators.
 *
 * @property state Its state.
 * @property launcher npm or pypi.
 * @property packageName The pinned package.
 * @property starts How many times it started.
 * @property failures How many times it failed.
 * @property lastExitCode The exit code of its last process.
 * @property lastFailure The host's code of its last failure.
 * @property coldStartMillis How long its last start took.
 * @property rssBytes The resident memory of its tree.
 * @property stderr The last of its scrubbed stderr.
 */
data class HostedProcessStatus(
    val state: String,
    val launcher: String,
    val packageName: String,
    val starts: Int = 0,
    val failures: Int = 0,
    val lastExitCode: Int? = null,
    val lastFailure: String? = null,
    val coldStartMillis: Long? = null,
    val rssBytes: Long? = null,
    val stderr: String = "",
)

/**
 * The host's whole status.
 *
 * @property version The host's version.
 * @param processes Each process the host knows, by the relay key ([UpstreamTarget.processKey]) it designates.
 */
class HostStatus(val version: String, private val processes: (String) -> HostedProcessStatus?) {
    /**
     * The process of a relay key, or null when the host does not know it (the process is idle).
     *
     * @param key The relay key.
     */
    fun of(key: String): HostedProcessStatus? = processes(key)
}

/**
 * The state the relay last recorded for one hosted process (`mcp_relay_stdio_states`), TeamCode's
 * `HostedProcessRecord`. Recorded for an earlier key (a new version, a reassignment), it reads as idle.
 *
 * @property orgId The org.
 * @property serverId The server.
 * @property memberId The member of a per-member server's process; null for a shared server's.
 * @property processKey The relay key it was observed under.
 * @property state Its state.
 * @property failure The last failure not yet followed by a successful start.
 * @property changedAt When it was written.
 */
data class HostedProcessRecord(
    val orgId: Uuid,
    val serverId: Uuid,
    val memberId: Uuid?,
    val processKey: String,
    val state: String,
    val failure: String?,
    val changedAt: Instant,
) {
    /**
     * The state of the process a key now designates.
     *
     * @param key The current relay key.
     */
    fun stateFor(key: String): String = if (key == processKey) state else HostedProcessStates.IDLE

    /**
     * The failure of the process a key now designates.
     *
     * @param key The current relay key.
     */
    fun failureFor(key: String): String? = if (key == processKey) failure else null
}

/**
 * One write of the state sweep: [next] replaces [previous] (null: no row yet) only when [previous] is still what is
 * stored, so two replicas never write the same transition twice.
 *
 * @property previous What the sweep read.
 * @property next What it writes.
 */
data class HostedProcessChange(val previous: HostedProcessRecord?, val next: HostedProcessRecord)

/** The state sweep's transitions, TeamCode's `HostedProcessStateRules`. */
object HostedProcessStateRules {
    /** Host failure codes are short labels (`start_failed`, `crashed`); anything else is not stored. */
    val FAILURE_CODE: Regex = Regex("[a-z0-9_]{1,32}")

    /**
     * The write for a process given what the host reported and what is stored, or null when nothing changes. An idle
     * process that never failed has no row.
     *
     * @param process The process.
     * @param status What the host says of it; null when it does not know it.
     * @param stored What the relay recorded.
     * @param now The time of the sweep.
     */
    fun change(
        process: HostedProcessRef,
        status: HostedProcessStatus?,
        stored: HostedProcessRecord?,
        now: Instant,
    ): HostedProcessChange? {
        val key = process.key
        val (state, failure) = observe(key, status, stored)
        // Without a stored row there is no failure to keep: an idle process then has nothing worth a row.
        val unchanged = if (stored == null) {
            state == HostedProcessStates.IDLE
        } else {
            stored.processKey == key && stored.state == state && stored.failure == failure
        }
        if (unchanged) return null
        val next = HostedProcessRecord(process.orgId, process.serverId, process.memberId, key, state, failure, now)
        return HostedProcessChange(stored, next)
    }

    /**
     * The state and failure of a process once the host has answered: a failing one keeps its last failure, a ready
     * one has none, any other keeps what was recorded.
     *
     * @param key The process key.
     * @param status What the host says of it; null when it does not know it (idle).
     * @param stored What the relay recorded.
     */
    fun observe(key: String, status: HostedProcessStatus?, stored: HostedProcessRecord?): Pair<String, String?> {
        val previousFailure = stored?.failureFor(key)
        val state = if (status == null) HostedProcessStates.IDLE else HostedProcessStates.known(status.state)
        val reported = status?.lastFailure?.takeIf { FAILURE_CODE.matches(it) }
        val failure = when {
            HostedProcessStates.failing(state) -> reported ?: previousFailure ?: state
            state == HostedProcessStates.READY -> null
            else -> previousFailure
        }
        return state to failure
    }
}

/**
 * One hosted process an assignment designates: a shared server's once, a per-member server's once per member.
 *
 * @property orgId The org.
 * @property serverId The server.
 * @property memberId The member of a per-member server's process; null for a shared server's.
 * @property key Its relay key.
 */
data class HostedProcessRef(val orgId: Uuid, val serverId: Uuid, val memberId: Uuid?, val key: String)

/** Expands stdio assignments into their processes, as the retain sweep and the administration read them. */
object HostedProcessExpansion {
    /**
     * The processes of some assignments, at most [limit]; null past it, or when an org-wide per-member assignment
     * cannot be expanded. A member's own row wins over the org-wide one, which applies to every other member of the
     * org;
     * a member who left the org keeps no process; without the member list, the own rows are all kept.
     *
     * @param assignments The assignments, of any orgs.
     * @param limit The most processes.
     * @param roster The members of an org, or null when they cannot be listed.
     */
    suspend fun expand(
        assignments: List<HostedAssignment>,
        limit: Int,
        roster: suspend (Uuid) -> Set<Uuid>?,
    ): List<HostedProcessRef>? {
        val processes = ArrayList<HostedProcessRef>()
        val rosters = HashMap<Uuid, Set<Uuid>?>()
        // Past the bound nothing is used anyway: stop expanding.
        for (rows in assignments.groupBy { it.serverId }.values) {
            val first = rows.first()
            val serverProcesses = if (first.sharing == McpSharing.SHARED) {
                listOf(first.ref(null))
            } else {
                perMember(rows, rosters.getOrPut(first.orgId) { roster(first.orgId) })
            }
            if (serverProcesses == null || processes.size + serverProcesses.size > limit) return null
            processes += serverProcesses
        }
        return processes
    }

    private fun perMember(rows: List<HostedAssignment>, roster: Set<Uuid>?): List<HostedProcessRef>? {
        val own = rows.mapNotNull { row -> row.memberId?.takeIf { roster == null || it in roster }?.let { it to row } }
            .toMap()
        val everyone = rows.firstOrNull { it.memberId == null }
        val others = if (everyone == null) {
            emptyList()
        } else {
            (roster ?: return null).filter { it !in own }.map { everyone.ref(it) }
        }
        return own.map { (memberId, row) -> row.ref(memberId) } + others
    }

    private fun HostedAssignment.ref(memberId: Uuid?): HostedProcessRef {
        val key = if (memberId == null) {
            StdioProcessKey.shared(orgId, serverId, version)
        } else {
            StdioProcessKey.of(orgId, serverId, version, sharing, memberId, assignedAt)
        }
        return HostedProcessRef(orgId, serverId, memberId, key)
    }
}

/**
 * One hosted process of the org, for administrators (`GET /api/v1/mcp/hosted`).
 *
 * @property server The server's name.
 * @property memberId The member of a per-member server's process.
 * @property launcher npm or pypi.
 * @property packageName The pinned package.
 * @property state Live from the host, else as recorded.
 * @property failure The last failure not yet followed by a successful start.
 * @property status What the host said, when it answered.
 */
data class HostedProcess(
    val server: String,
    val memberId: Uuid?,
    val launcher: String,
    val packageName: String,
    val state: String,
    val failure: String?,
    val status: HostedProcessStatus?,
)

/**
 * The org's hosted processes.
 *
 * @property hostReachable False when the host did not answer; the states are then the recorded ones.
 * @property hostVersion The host's version, when it answered.
 * @property processes By server, then member.
 */
data class HostedProcesses(val hostReachable: Boolean, val hostVersion: String?, val processes: List<HostedProcess>)

/** The org has more hosted assignments than can be read at once. */
class McpHostedTooManyException :
    OstromException(
        ErrorKind.CONFLICT,
        "This organization's hosted MCP processes cannot be listed: more than 10,000, or its members cannot be read.",
        "mcp_hosted_too_many",
    )

/** The MCP host did not answer, or refused the relay. */
class McpHostUnavailableException :
    OstromException(ErrorKind.UNAVAILABLE, "The MCP host did not answer. Retry shortly.", "mcp_host_unavailable")

/** The org's hosted processes, read live from the host (TeamCode's "Hosted commands"). */
interface ListHostedProcessesUseCase {
    /**
     * Lists them.
     *
     * @param caller An administrator.
     */
    suspend operator fun invoke(caller: Caller): HostedProcesses
}

/** Restarts one hosted process: the host stops it and lifts its backoff (TeamCode's "Restart"). */
interface RestartHostedProcessUseCase {
    /**
     * Restarts it.
     *
     * @param caller An administrator.
     * @param server The stdio server's name.
     * @param memberId The member, for a per-member server; ignored for a shared one.
     * @param journal Writes the restart to the audit log, before the stop: a stop that times out after the host acted
     *   is still on record.
     */
    suspend operator fun invoke(caller: Caller, server: String, memberId: Uuid?, journal: suspend () -> Unit)
}

/**
 * [ListHostedProcessesUseCase] and [RestartHostedProcessUseCase] on the repository, the org's members and the host.
 *
 * @param repository The servers, assignments and recorded states.
 * @param members Who belongs to the org, for a per-member server assigned to everyone.
 * @param host The MCP host, or null when none is configured.
 */
class HostedProcessAdministration(
    private val repository: McpRelayRepository,
    private val members: MemberDirectory?,
    private val host: McpHostControl?,
) : ListHostedProcessesUseCase, RestartHostedProcessUseCase {
    override suspend fun invoke(caller: Caller): HostedProcesses {
        caller.requireAdmin()
        val host = host ?: throw McpHostDisabledException()
        val (servers, processes) = processes(caller.orgId)
        val records = repository.stdioStates(caller.orgId, MAX_RECORDS).orEmpty()
            .associateBy { it.serverId to it.memberId }
        // Read after the database; an unreachable host still shows the recorded states.
        val status = try {
            host.status()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        val items = processes.mapNotNull { process ->
            val server = servers.getValue(process.serverId)
            // A stdio server always has its command; one without is not a process the host can run.
            val stdio = server.stdio ?: return@mapNotNull null
            val record = records[process.serverId to process.memberId]
            val live = status?.of(process.key)
            // When the host answered, it is the truth (a process it does not know is idle); otherwise the record.
            val (state, failure) = if (status != null) {
                HostedProcessStateRules.observe(process.key, live, record)
            } else if (record == null) {
                HostedProcessStates.IDLE to null
            } else {
                record.stateFor(process.key) to record.failureFor(process.key)
            }
            HostedProcess(
                server = server.name,
                memberId = process.memberId,
                launcher = stdio.launcher.wire,
                packageName = stdio.packageName,
                state = state,
                failure = failure,
                status = live?.copy(stderr = live.stderr.takeLast(MAX_STDERR)),
            )
        }.sortedWith(compareBy({ it.server }, { it.memberId }))
        return HostedProcesses(status != null, status?.version, items)
    }

    override suspend fun invoke(caller: Caller, server: String, memberId: Uuid?, journal: suspend () -> Unit) {
        caller.requireAdmin()
        val host = host ?: throw McpHostDisabledException()
        val (servers, processes) = processes(caller.orgId)
        val process = processes.firstOrNull { process ->
            servers.getValue(process.serverId).name == server && process.memberId in setOf(null, memberId)
        } ?: throw McpServerNotFoundException()
        journal()
        val stopped = try {
            host.stop(process.key)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        stopped ?: throw McpHostUnavailableException()
    }

    private suspend fun processes(orgId: Uuid): Pair<Map<Uuid, McpServer>, List<HostedProcessRef>> {
        val rows = repository.orgHostedAssignments(orgId, MAX_PROCESSES) ?: throw McpHostedTooManyException()
        val servers = rows.associate { it.server.id to it.server }
        val assignments = rows.map {
            val server = it.server
            val (memberId, assignedAt) = it.assignment.memberId to it.assignment.assignedAt
            HostedAssignment(orgId, server.id, server.version, server.sharing, memberId, assignedAt)
        }
        val processes = HostedProcessExpansion.expand(assignments, MAX_PROCESSES) { roster(it) }
            ?: throw McpHostedTooManyException()
        return servers to processes
    }

    private suspend fun roster(orgId: Uuid): Set<Uuid>? =
        members?.memberIds(orgId, MAX_PROCESSES)?.mapNotNullTo(HashSet()) { Uuid.parseOrNull(it) }

    /** The administration's bounds. */
    companion object {
        /** As the retain sweep's. */
        const val MAX_PROCESSES: Int = RetainHostedMcpProcessesUseCaseImpl.MAX_KEYS

        /** At most one row per assignment and one per shared process. */
        const val MAX_RECORDS: Int = MAX_PROCESSES * 2

        /** The stderr shown per process: its last 4 KiB (the host keeps 64). */
        const val MAX_STDERR: Int = 4 * 1024
    }
}
