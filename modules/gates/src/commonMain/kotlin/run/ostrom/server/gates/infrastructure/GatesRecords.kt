// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gates.infrastructure

import run.ostrom.server.gates.domain.models.AnswerChannel
import run.ostrom.server.gates.domain.models.ApprovalRequest
import run.ostrom.server.gates.domain.models.AskedVia
import run.ostrom.server.gates.domain.models.Evidence
import run.ostrom.server.gates.domain.models.Gate
import run.ostrom.server.gates.domain.models.GateMode
import run.ostrom.server.gates.domain.models.GateNode
import run.ostrom.server.gates.domain.models.GatePullRequest
import run.ostrom.server.gates.domain.models.GatePush
import run.ostrom.server.gates.domain.models.GateStatus
import run.ostrom.server.gates.domain.models.GateSubject
import run.ostrom.server.gates.domain.models.PushRef
import run.ostrom.server.gates.domain.models.RequestStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder

/*
 * How a gate and a request are stored: their rows ([GatesGates], [GatesRequests]) and the JSON of their text columns,
 * written and read by the module's own serializer. The domain never sees a row.
 */

private val json = Json { ignoreUnknownKeys = true }
private val strings = ListSerializer(String.serializer())
private val evidences = ListSerializer(EvidenceRecord.serializer())

/** The asker columns of [gate] (who asks, by which path, from which sessions and launches): `addAskers` writes them. */
internal fun UpdateBuilder<*>.setAskers(gate: Gate) {
    val g = GatesGates
    this[g.askedBy] = json.encodeToString(strings, gate.askedBy)
    this[g.askedVia] = json.encodeToString(strings, gate.askedVia.map { it.name }.sorted())
    this[g.sessionIds] = json.encodeToString(strings, gate.sessionIds)
    this[g.launchIds] = json.encodeToString(strings, gate.launchIds)
    this[g.unlinkedAuthor] = gate.unlinkedAuthor
}

/** Every column of [gate] but its id and organization, which an update keeps. */
internal fun UpdateBuilder<*>.setGate(gate: Gate) {
    val g = GatesGates
    this[g.key] = gate.key
    this[g.subject] = json.encodeToString(SubjectRecord.serializer(), SubjectRecord.of(gate.subject))
    this[g.node] = gate.node?.let { json.encodeToString(NodeRecord.serializer(), NodeRecord.of(it)) }
    this[g.label] = gate.label
    this[g.mode] = gate.mode.name
    this[g.status] = gate.status.name
    setAskers(gate)
    this[g.shownDigest] = gate.shownDigest
    this[g.evidence] = json.encodeToString(evidences, gate.evidence.map { EvidenceRecord(it.label, it.value) })
    this[g.decidedBy] = gate.decidedBy
    this[g.decidedAt] = gate.decidedAt
    this[g.selfApproved] = gate.selfApproved
    this[g.receiptId] = gate.receiptId
    this[g.reservedBy] = gate.reservedBy
    this[g.reservedAt] = gate.reservedAt
    this[g.consumedAt] = gate.consumedAt
    this[g.releasedAt] = gate.releasedAt
    this[g.createdAt] = gate.createdAt
}

/** The columns a gate is read with: every one of [GatesGates]. */
internal val GATE_COLUMNS = GatesGates.columns

/** The columns a request is read with: every one of [GatesRequests] but the generated `approver_key`. */
internal val REQUEST_COLUMNS = GatesRequests.columns - GatesRequests.approverKey

/** The gate of a row read with [GATE_COLUMNS]. */
internal fun ResultRow.toGate(): Gate {
    val g = GatesGates
    return Gate(
        id = this[g.id],
        orgId = this[g.orgId],
        key = this[g.key],
        subject = json.decodeFromString(SubjectRecord.serializer(), this[g.subject]).toSubject(),
        node = this[g.node]?.let { json.decodeFromString(NodeRecord.serializer(), it).toNode() },
        label = this[g.label],
        mode = GateMode.valueOf(this[g.mode]),
        status = GateStatus.valueOf(this[g.status]),
        askedBy = json.decodeFromString(strings, this[g.askedBy]),
        askedVia = json.decodeFromString(strings, this[g.askedVia]).map { AskedVia.valueOf(it) }.toSet(),
        sessionIds = json.decodeFromString(strings, this[g.sessionIds]),
        launchIds = json.decodeFromString(strings, this[g.launchIds]),
        shownDigest = this[g.shownDigest],
        evidence = json.decodeFromString(evidences, this[g.evidence]).map { Evidence(it.label, it.value) },
        decidedBy = this[g.decidedBy],
        decidedAt = this[g.decidedAt],
        selfApproved = this[g.selfApproved],
        receiptId = this[g.receiptId],
        reservedBy = this[g.reservedBy],
        reservedAt = this[g.reservedAt],
        consumedAt = this[g.consumedAt],
        releasedAt = this[g.releasedAt],
        createdAt = this[g.createdAt],
        unlinkedAuthor = this[g.unlinkedAuthor],
    )
}

/** The request of a row read with [REQUEST_COLUMNS]. */
internal fun ResultRow.toRequest(): ApprovalRequest {
    val r = GatesRequests
    return ApprovalRequest(
        id = this[r.id],
        gateId = this[r.gateId],
        orgId = this[r.orgId],
        approverId = this[r.approverId],
        status = RequestStatus.valueOf(this[r.status]),
        answeredAt = this[r.answeredAt],
        channel = this[r.channel]?.let { AnswerChannel.valueOf(it) },
        createdAt = this[r.createdAt],
    )
}

/** The stored form of a [GateSubject]. */
@Serializable
internal data class SubjectRecord(
    val repo: String? = null,
    val realRepo: String? = null,
    val actionClass: String,
    val command: String,
    val scope: String,
    val push: PushRecord? = null,
    val pullRequest: PullRequestRecord? = null,
    val commits: List<String> = emptyList(),
    val diffDigest: String? = null,
    val tool: String = "",
    val harnessVersion: String? = null,
    val runId: String? = null,
) {
    fun toSubject() = GateSubject(
        repo = repo,
        realRepo = realRepo,
        actionClass = actionClass,
        command = command,
        scope = scope,
        push = push?.let { record ->
            GatePush(record.remote, record.refs.map { PushRef(it.ref, it.sha) }, record.force)
        },

        pullRequest = pullRequest?.let { GatePullRequest(it.number, it.headSha, it.base) },
        commits = commits,
        diffDigest = diffDigest,
        tool = tool,
        harnessVersion = harnessVersion,
        runId = runId,
    )

    companion object {
        fun of(subject: GateSubject) = SubjectRecord(
            repo = subject.repo,
            realRepo = subject.realRepo,
            actionClass = subject.actionClass,
            command = subject.command,
            scope = subject.scope,
            push = subject.push?.let { push ->
                PushRecord(push.remote, push.refs.map { PushRefRecord(it.ref, it.sha) }, push.force)
            },
            pullRequest = subject.pullRequest?.let { PullRequestRecord(it.number, it.headSha, it.base) },
            commits = subject.commits,
            diffDigest = subject.diffDigest,
            tool = subject.tool,
            harnessVersion = subject.harnessVersion,
            runId = subject.runId,
        )
    }
}

/** The stored form of a [GatePush]. */
@Serializable
internal data class PushRecord(val remote: String, val refs: List<PushRefRecord>, val force: Boolean)

/** The stored form of a [PushRef]. */
@Serializable
internal data class PushRefRecord(val ref: String, val sha: String)

/** The stored form of a [GatePullRequest]. */
@Serializable
internal data class PullRequestRecord(val number: Int, val headSha: String, val base: String)

/** The stored form of a [GateNode]. */
@Serializable
internal data class NodeRecord(
    val workflowId: String,
    val nodeId: String,
    val name: String,
    val approvers: List<String>,
    val enforced: Boolean,
    val workflowName: String,
    val workflowStatus: String,
    val basis: String,
    val reason: String,
) {
    fun toNode() = GateNode(workflowId, nodeId, name, approvers, enforced, workflowName, workflowStatus, basis, reason)

    companion object {
        fun of(node: GateNode) = NodeRecord(
            node.workflowId,
            node.nodeId,
            node.name,
            node.approvers,
            node.enforced,
            node.workflowName,
            node.workflowStatus,
            node.basis,
            node.reason,
        )
    }
}

/** The stored form of an [Evidence] line. */
@Serializable
internal data class EvidenceRecord(val label: String, val value: String)
