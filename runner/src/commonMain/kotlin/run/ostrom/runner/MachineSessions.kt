// SPDX-License-Identifier: Apache-2.0
package run.ostrom.runner

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid

/**
 * The cloud sessions a registered runner runs on its machine's Docker Engine.
 *
 * Each heartbeat reports what the engine holds ([report]) and gets back what the runner must hold. The plans it
 * brings are kept at once ([receive]: each comes once), then [apply] creates them and deletes the runner's own
 * containers and volumes the server no longer wants. Creating an execution may take minutes (an image pull), so the
 * heartbeats go on meanwhile: an execution being created is reported `pending`.
 *
 * @param engine The machine's engine, confined to this runner's objects.
 * @param guard What the machine accepts to create.
 * @param maxSessions The sessions it runs at once at most (`OSTROM_RUNNER_MAX_SESSIONS`); when null, what the machine
 *   holds, read once from the engine: 2 processors and 4.5 GiB a session (its agent's 4 GiB and its runner's 256 MiB,
 *   with room to spare), one at least. An engine that does not show them (a socket proxy with `INFO=0`) leaves the
 *   capacity unknown: no limit, said once in the log.
 * @param log Writes a line to the process's log (never a token).
 */
class MachineSessions(
    private val engine: DockerEngine,
    private val guard: PlanGuard,
    private val maxSessions: Int?,
    private val log: (String) -> Unit,
) {
    private var capacity: Int? = maxSessions
    private var capacityKnown = maxSessions != null
    private val mutex = Mutex()
    private val plans = mutableMapOf<Execution, ExecutionPlan>()

    /**
     * What the runner has: its executions and the plans it is creating, and its workspaces.
     *
     * @return The report.
     */
    suspend fun report(): MachineReport {
        // The plans first: one created meanwhile is then in the listing that follows.
        val waiting = mutex.withLock { plans.keys.toSet() }
        val containers = engine.containers()
        val listed = containers.groupBy { Execution(it.sessionId, it.epoch) }.map { (execution, held) ->
            // The runner's container is the one named after the execution; without it, the execution has ended.
            val runner = held.firstOrNull { it.name == execution.containerName }
            execution to stateOf(runner?.state)
        }.toMap()
        // An execution being created is pending, whatever part of it the engine already shows.
        val executions = listed + waiting.associateWith { PENDING }
        return MachineReport(
            executions.map { (execution, state) ->
                ReportedExecution(execution.sessionId.toString(), execution.epoch, state)
            },
            engine.volumes().map { it.sessionId.toString() }.distinct(),
            if (capacityKnown) capacity else readCapacity(),
        )
    }

    private suspend fun readCapacity(): Int? {
        val read = try {
            capacityOf(engine.info())
        } catch (refused: DockerApiException) {
            log("${refused.message} No capacity declared: set OSTROM_RUNNER_MAX_SESSIONS.")
            null
        }
        capacity = read
        capacityKnown = true
        return read
    }

    /**
     * Keeps the plans of a heartbeat's answer, and forgets those the server no longer wants created.
     *
     * @param work The answer.
     */
    suspend fun receive(work: MachineWork) {
        val wanted = work.executions.associateBy { Execution(Uuid.parse(it.sessionId), it.epoch) }
        mutex.withLock {
            plans.keys.retainAll(wanted.keys)
            wanted.forEach { (execution, assigned) -> assigned.plan?.let { plans[execution] = it } }
        }
    }

    /**
     * Brings the engine to what the server wants: the plans received are created, and this runner's containers and
     * volumes the server no longer lists are deleted.
     *
     * @param work The server's latest answer.
     */
    suspend fun apply(work: MachineWork) {
        val wanted = work.executions.map { Execution(Uuid.parse(it.sessionId), it.epoch) }.toSet()
        for (execution in wanted) {
            val plan = mutex.withLock { plans[execution] } ?: continue
            create(execution, plan)
            mutex.withLock { plans.remove(execution) }
        }
        for (container in engine.containers()) {
            if (Execution(container.sessionId, container.epoch) !in wanted) engine.removeContainer(container.id)
        }
        val kept = work.workspaces.map(Uuid::parse).toSet() + wanted.map { it.sessionId }
        for (volume in engine.volumes()) {
            if (volume.sessionId !in kept) engine.removeVolume(volume.name)
        }
    }

    /** Creates an execution; a refused plan or a failed creation is logged, then reported missing to the server. */
    private suspend fun create(execution: Execution, plan: ExecutionPlan) {
        try {
            val checked = guard.check(plan)
            checked.volumes.forEach { engine.createVolume(it) }
            checked.containers.forEach { engine.startContainer(it) }
            log("started $execution")
        } catch (error: CancellationException) {
            throw error
        } catch (error: IllegalArgumentException) {
            log("refused the plan of $execution: ${error.message}")
        } catch (error: Exception) {
            log("could not start $execution: ${error::class.simpleName}: ${error.message}")
        }
    }

    /** One execution of a session. */
    private data class Execution(val sessionId: Uuid, val epoch: Long) {
        /** The name of its runner's container, the one the server's plan gives it. */
        val containerName: String get() = "ostrom-s-${sessionId.toHexString()}-e$epoch"

        override fun toString(): String = "session $sessionId epoch $epoch"
    }

    /** Constants and readers of the enclosing type. */
    private companion object {
        const val PENDING = "pending"

        /** The processors a session takes at most (its agent's limit). */
        const val SESSION_CPUS = 2

        /** The memory a session takes at most, 4.5 GiB: its agent's 4 GiB, its runner's 256 MiB, and room. */
        const val SESSION_MEMORY_BYTES = 9L shl 29

        /** The sessions a machine holds, one at least. */
        fun capacityOf(info: EngineInfo): Int =
            maxOf(1, minOf(info.cpus / SESSION_CPUS, (info.memoryBytes / SESSION_MEMORY_BYTES).toInt()))

        /** The engine's container state, as the server reads it. */
        fun stateOf(state: String?): String = when (state) {
            "running", "paused", "restarting" -> "running"
            "created" -> PENDING
            else -> "ended"
        }
    }
}
