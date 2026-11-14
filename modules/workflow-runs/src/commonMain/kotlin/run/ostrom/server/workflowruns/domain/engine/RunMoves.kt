// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.workflowruns.domain.engine

import run.ostrom.core.api.models.NodeVisit
import run.ostrom.core.api.models.RunStatus
import run.ostrom.core.api.models.WorkflowNode
import run.ostrom.core.api.models.WorkflowNodeKind
import run.ostrom.core.api.models.WorkflowRun
import run.ostrom.server.kernel.audit.auditDetails
import run.ostrom.server.receipts.domain.usecases.isoMillis
import run.ostrom.server.workflowruns.domain.models.RunAudit
import run.ostrom.server.workflowruns.domain.models.RunChange
import run.ostrom.server.workflowruns.domain.models.RunGraph
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The changes the engine makes to a run, without any effect: start it, begin a visit, finish it, follow an edge, end.
 * Each one is a [RunChange] the repository writes only if the run is still as it was read.
 *
 * @param owner The engine replica that holds the run's lease.
 * @param now The time of the change.
 */
class RunMoves(private val owner: String, now: Instant) {
    private val at = now.isoMillis()

    /**
     * Starts a queued run at its graph's start node, or its first node.
     *
     * @param run The run.
     * @param graph The graph it copied.
     *
     * @return The change: the first visit begun, or the run failed when it has nothing to play.
     */
    fun start(run: WorkflowRun, graph: RunGraph): RunChange {
        val first = graph.start ?: graph.nodes.firstOrNull()?.id
        return if (first == null) end(run, RunStatus.FAILED, NOTHING_TO_PLAY) else begin(run, graph, first)
    }

    /**
     * Begins a visit of a node, within its `maxVisits` (1 when absent: a loop declares how often it goes round) and
     * the most visits a run makes.
     *
     * @param run The run.
     * @param graph The graph it copied.
     * @param nodeId The node.
     *
     * @return The change: the visit begun, `running`, or the run failed.
     */
    fun begin(run: WorkflowRun, graph: RunGraph, nodeId: String): RunChange {
        val node = graph.nodes.firstOrNull { it.id == nodeId }
        val most = node?.maxVisits ?: 1
        return when {
            node == null -> end(run, RunStatus.FAILED, "Node ${nodeId.take(ID)} is not in the workflow.")
            run.visits.count { it.nodeId == nodeId } >= most ->
                end(run, RunStatus.FAILED, "Node ${node.id} was visited $most time(s), its maxVisits.")
            run.visits.size >= MAX_VISITS -> end(run, RunStatus.FAILED, "The run made $MAX_VISITS visits, the most.")
            else -> visit(run, node)
        }
    }

    private fun visit(run: WorkflowRun, node: WorkflowNode): RunChange {
        val seq = run.visits.size + 1
        // The session's key is in the audit and in the visit's row from the start; the run names it only once the
        // session exists ([dispatched]), so a read in between never finds an id that no session has yet, nor the
        // session of an earlier agent visit.
        val session = if (node.kind == WorkflowNodeKind.AGENT) sessionOf(run.id, seq).toString() else null
        val visit = NodeVisit(seq, node.id, node.kind, RUNNING, nodeTitle = node.label, startedAt = at)
        val next = run.copy(
            status = RunStatus.RUNNING,
            currentNodeId = node.id,
            sessionId = if (session == null) run.sessionId else null,
            visits = run.visits + visit,
        )
        val details = auditDetails("node" to node.id, "kind" to node.kind.value, "seq" to seq, "session" to session)
        return RunChange(run, next, owner, visit, listOf(RunAudit("node_started", details)))
    }

    /**
     * Finishes the visit under way with its outcome.
     *
     * @param run The run.
     * @param outcome What the node did: the outcome that chooses the next edge.
     * @param failed True when the node failed (the visit is `failed`, else `done`).
     * @param output What the visit says: a delivery whole (its node bounds it), anything else cut to a line.
     *
     * @return The change.
     */
    fun finish(run: WorkflowRun, outcome: String, failed: Boolean = false, output: String? = null): RunChange {
        val done = run.visits.last().copy(
            status = if (failed) FAILED else DONE,
            outcome = outcome,
            output = if (outcome == DELIVERED) output else output?.take(OUTPUT),
            endedAt = at,
        )
        return RunChange(run, run.copy(status = RunStatus.RUNNING, visits = run.visits.dropLast(1) + done), owner)
    }

    /**
     * Names the cloud session the agent visit under way asked, once it exists: on the visit and on the run (TeamCode's
     * `dispatched`). The visit's key already named it when it began, and its audit line too.
     *
     * @param run The run.
     * @param sessionId The session.
     *
     * @return The change.
     */
    fun dispatched(run: WorkflowRun, sessionId: Uuid): RunChange {
        val session = sessionId.toString()
        val named = run.visits.last().copy(sessionId = session)
        return RunChange(run, run.copy(sessionId = session, visits = run.visits.dropLast(1) + named), owner)
    }

    /**
     * Marks the visit under way as holding what it started: a gate or a wait (the visit is `waiting`, and the run
     * too), or a child run (the visit stays `running`, and the run too).
     *
     * @param run The run.
     * @param held The visit, with the gate, the wait or the child run it holds.
     * @param event The audit event (`gate_opened`, `wait_asked`, `child_started`).
     * @param detail The audit fact that names what it holds.
     *
     * @return The change.
     */
    fun held(run: WorkflowRun, held: NodeVisit, event: String, detail: Pair<String, Any?>): RunChange {
        val status = if (held.status == WAITING) RunStatus.WAITING else RunStatus.RUNNING
        val next = run.copy(status = status, visits = run.visits.dropLast(1) + held)
        val details = auditDetails("node" to held.nodeId, "seq" to held.seq, detail)
        return RunChange(run, next, owner, audit = listOf(RunAudit(event, details)))
    }

    /**
     * Follows the edge the last visit's outcome takes: its own `on`, else, for a positive outcome (`next`,
     * `delivered`, `approved`, or any answer of a wait: the action its person chose), an edge without `on`. A node
     * without any edge ends the run on a positive outcome, whose output is the run's; an outcome no edge takes fails
     * it, with the stable code of the failure when it has one ([failureCodeOf]).
     *
     * @param run The run, its last visit finished.
     * @param graph The graph it copied.
     *
     * @return The change.
     */
    fun follow(run: WorkflowRun, graph: RunGraph): RunChange {
        val last = run.visits.last()
        val out = graph.edges.filter { it.from == last.nodeId }
        val edge = out.firstOrNull { it.on == last.outcome }
            ?: out.firstOrNull { it.on in DEFAULT_ON && positive(last) }
        val to = edge?.to
        return when {
            edge == null && out.isEmpty() && positive(last) -> end(run, RunStatus.SUCCEEDED, null, output = last.output)
            edge == null -> end(
                run,
                RunStatus.FAILED,
                "Node ${last.nodeId}: ${last.outcome}${detail(last.output)}",
                failureCodeOf(last),
            )
            to == null -> end(run, RunStatus.SUCCEEDED, null, output = last.output)
            else -> begin(run, graph, to)
        }
    }

    /**
     * Ends the run.
     *
     * @param run The run.
     * @param status `succeeded` or `failed`.
     * @param failure Why it failed.
     * @param code Why it failed, as a stable code, when it has one.
     * @param output What the run says: its last visit's output, when it succeeds.
     *
     * @return The change.
     */
    fun end(
        run: WorkflowRun,
        status: RunStatus,
        failure: String?,
        code: String? = null,
        output: String? = null,
    ): RunChange {
        val next = run.copy(
            status = status,
            currentNodeId = null,
            failure = failure,
            failureCode = code,
            output = output,
            endedAt = at,
        )
        val details = auditDetails("status" to status.value, "failure" to failure, "code" to code)
        return RunChange(run, next, owner, audit = listOf(RunAudit("ended", details)))
    }

    // A wait's answer is positive whatever its action: its person answered.
    private fun positive(last: NodeVisit): Boolean =
        last.outcome in POSITIVE || last.kind == WorkflowNodeKind.WAIT && last.status == DONE

    private fun detail(output: String?): String = if (output == null) "." else " (${output.take(OUTPUT)})."

    /** The vocabulary of a run's visits and outcomes. */
    companion object {
        /** A visit under way. */
        const val RUNNING: String = "running"

        /** A visit waiting for a person (a gate, a wait). */
        const val WAITING: String = "waiting"

        /** A visit finished. */
        const val DONE: String = "done"

        /** A visit that failed. */
        const val FAILED: String = "failed"

        /** The outcome of a node that did its part. */
        const val NEXT: String = "next"

        /** The outcome of an agent node whose delivery came, and of a workflow node whose child run succeeded. */
        const val DELIVERED: String = "delivered"

        /** Why an agent visit fails when its session failed to start. */
        const val SESSION_FAILED: String = "The agent session failed to start."

        /** Why an agent visit that asks for a delivery fails when its session stopped working with none. */
        const val ENDED_WITHOUT_DELIVERY: String = "The agent stopped without delivering what the step asks for."

        /** The outcome of a gate an approver said yes to. */
        const val APPROVED: String = "approved"

        /** The outcome of a gate an approver said no to. */
        const val REJECTED: String = "rejected"

        /** The most visits a run makes, whatever its loops. */
        const val MAX_VISITS: Int = 100

        /** Why a run without a node fails. */
        const val NOTHING_TO_PLAY: String = "Nothing to play: the run copied no node of its workflow."

        private const val ID = 200
        private const val OUTPUT = 500
    }
}

// Top-level, not in the companion: a private companion property has a getter nothing calls.
private val DEFAULT_ON = setOf(null, RunMoves.NEXT)
private val POSITIVE = setOf(RunMoves.NEXT, RunMoves.DELIVERED, RunMoves.APPROVED)
private val FAILURE_CODES = mapOf(
    RunMoves.SESSION_FAILED to "session_failed",
    RunMoves.ENDED_WITHOUT_DELIVERY to "ended_without_delivery",
)

/**
 * The stable code of a failed visit (`WorkflowRun.failureCode`): the failures an agent visit names, read from what it
 * says; null for any other visit.
 *
 * @param visit The visit.
 *
 * @return The code, or null.
 */
fun failureCodeOf(visit: NodeVisit): String? =
    visit.output?.takeIf { visit.status == RunMoves.FAILED }?.let { FAILURE_CODES[it] }

/**
 * The cloud session of an agent node's visit: derived from the run and the visit, so the key exists before the
 * session does, and a start asked again after a restart finds the first one instead of opening a second.
 *
 * @param runId The run (`wfr_` and 32 hexadecimal digits).
 * @param seq The visit.
 *
 * @return The session's id.
 */
fun sessionOf(runId: String, seq: Int): Uuid =
    Uuid.parseHex(runId.removePrefix("wfr_")).toLongs { high, low -> Uuid.fromLongs(high, low xor seq.toLong()) }
