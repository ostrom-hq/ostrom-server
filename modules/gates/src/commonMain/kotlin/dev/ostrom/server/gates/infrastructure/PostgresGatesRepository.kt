// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gates.infrastructure

import dev.ostrom.server.gates.domain.models.AnswerChannel
import dev.ostrom.server.gates.domain.models.ApprovalRequest
import dev.ostrom.server.gates.domain.models.ApprovalView
import dev.ostrom.server.gates.domain.models.AskedVia
import dev.ostrom.server.gates.domain.models.Evidence
import dev.ostrom.server.gates.domain.models.Gate
import dev.ostrom.server.gates.domain.models.GateMode
import dev.ostrom.server.gates.domain.models.GateNode
import dev.ostrom.server.gates.domain.models.GatePullRequest
import dev.ostrom.server.gates.domain.models.GatePush
import dev.ostrom.server.gates.domain.models.GateStatus
import dev.ostrom.server.gates.domain.models.GateSubject
import dev.ostrom.server.gates.domain.models.PushRef
import dev.ostrom.server.gates.domain.models.RequestStatus
import dev.ostrom.server.gates.domain.repositories.GatesRepository
import dev.ostrom.server.gates.domain.repositories.GatesTransaction
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asBoolean
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstantOrNull
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.reflect.KClass
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [GatesRepository] on PostgreSQL (sqlx4k). Every call is one transaction that first sets `ostrom.org_id`, which the
 * row level security policies of the gates tables read: a query can never see or write another organization's rows,
 * even if its `WHERE` clause forgot the organization.
 *
 * @param driver The connection pool.
 */
class PostgresGatesRepository(private val driver: Driver) : GatesRepository {
    override suspend fun <T> transaction(block: suspend GatesTransaction.() -> T): T = driver.transaction {
        Tx(this).block()
    }

    override suspend fun get(orgId: Uuid, id: Uuid): Gate? = driver.transaction {
        Tx(this).run { scoped(orgId); executor.selectGate(orgId, id, lock = false) }
    }

    override suspend fun requestsOf(orgId: Uuid, gateId: Uuid): List<ApprovalRequest> = driver.transaction {
        Tx(this).requestsOf(orgId, gateId)
    }

    override suspend fun approval(orgId: Uuid, id: Uuid): ApprovalView? = driver.transaction {
        Tx(this).run {
            // The foreign key keeps a request's gate: read as the one gate it must be.
            request(orgId, id)?.let { request ->
                ApprovalView(request, listOfNotNull(executor.selectGate(orgId, request.gateId, lock = false)).single())
            }
        }
    }

    override suspend fun approvals(
        orgId: Uuid,
        approverId: String?,
        status: RequestStatus?,
        limit: Int,
    ): List<ApprovalView> = driver.transaction {
        Tx(this).run {
            scoped(orgId)
            val filters = buildString {
                append("org_id = :org")
                // Identities compare without case everywhere (`SelfApproval.same`), the inbox too.
                if (approverId != null) append(" AND lower(approver_id) = lower(:approver)")
                if (status != null) append(" AND status = :status")
            }
            val page = Statement.create(
                "SELECT $REQUEST_COLUMNS FROM gates_requests WHERE $filters " +
                    "ORDER BY created_at DESC, id DESC LIMIT :limit",
            ).bind("org", orgId).bind("limit", limit)
            approverId?.let { page.bind("approver", it) }
            status?.let { page.bind("status", it.name) }
            val requests = executor.rows(page).map { it.toRequest() }
            val gateIds = requests.map { it.gateId }.distinct()
            // One read for every gate of the page (at most `limit` ids, each bound): no query per request.
            val gates = if (gateIds.isEmpty()) {
                emptyMap()
            } else {
                val names = gateIds.indices.joinToString(", ") { ":g$it" }
                val statement = Statement.create(
                    "SELECT $GATE_COLUMNS FROM gates_gates WHERE org_id = :org AND id IN ($names)",
                ).bind("org", orgId)
                gateIds.forEachIndexed { index, gateId -> statement.bind("g$index", gateId) }
                executor.rows(statement).map { it.toGate() }.associateBy { it.id }
            }
            // Every request has its gate (foreign key, gates are never deleted).
            requests.map { ApprovalView(it, gates.getValue(it.gateId)) }
        }
    }

    private class Tx(val executor: QueryExecutor) : GatesTransaction {
        private var org: Uuid? = null

        suspend fun scoped(orgId: Uuid) {
            if (org == orgId) return
            executor.fetchAll(
                Statement.create("SELECT set_config('ostrom.org_id', :org, TRUE)").bind("org", orgId.toString()),
            ).getOrThrow()
            org = orgId
        }

        override suspend fun lockKey(orgId: Uuid, key: String) {
            scoped(orgId)
            executor.fetchAll(
                Statement.create("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))").bind("key", key),
            ).getOrThrow()
        }

        override suspend fun byKey(orgId: Uuid, key: String, limit: Int): List<Gate> {
            scoped(orgId)
            return executor.rows(
                Statement.create(
                    "SELECT $GATE_COLUMNS FROM gates_gates WHERE org_id = :org AND gate_key = :key " +
                        "ORDER BY created_at DESC, id DESC LIMIT :limit",
                ).bind("org", orgId).bind("key", key).bind("limit", limit),
            ).map { it.toGate() }
        }

        override suspend fun lockGate(orgId: Uuid, id: Uuid): Gate? {
            scoped(orgId)
            return executor.selectGate(orgId, id, lock = true)
        }

        override suspend fun request(orgId: Uuid, id: Uuid): ApprovalRequest? {
            scoped(orgId)
            return executor.rows(
                Statement.create("SELECT $REQUEST_COLUMNS FROM gates_requests WHERE org_id = :org AND id = :id")
                    .bind("org", orgId).bind("id", id),
            ).firstOrNull()?.toRequest()
        }

        override suspend fun requestsOf(orgId: Uuid, gateId: Uuid): List<ApprovalRequest> {
            scoped(orgId)
            return executor.rows(
                Statement.create(
                    "SELECT $REQUEST_COLUMNS FROM gates_requests WHERE org_id = :org AND gate_id = :gate " +
                        "ORDER BY position LIMIT $MAX_REQUESTS",
                ).bind("org", orgId).bind("gate", gateId),
            ).map { it.toRequest() }
        }

        override suspend fun insertGate(gate: Gate) {
            scoped(gate.orgId)
            executor.execute(
                Statement.create(
                    "INSERT INTO gates_gates ($GATE_COLUMNS) VALUES (:id, :org_id, :gate_key, :subject, :node, " +
                        ":label, :mode, :status, :asked_by, :asked_via, :session_ids, :launch_ids, :shown_digest, " +
                        ":evidence, :decided_by, :decided_at, :self_approved, :receipt_id, :reserved_by, " +
                        ":reserved_at, :consumed_at, :created_at)",
                ).bindGate(gate),
            ).getOrThrow()
        }

        override suspend fun addAskers(gate: Gate) {
            scoped(gate.orgId)
            executor.execute(
                Statement.create(
                    "UPDATE gates_gates SET asked_by = :asked_by, asked_via = :asked_via, " +
                        "session_ids = :session_ids, launch_ids = :launch_ids WHERE org_id = :org_id AND id = :id",
                ).bind("asked_by", json.encodeToString(strings, gate.askedBy))
                    .bind("asked_via", json.encodeToString(strings, gate.askedVia.map { it.name }.sorted()))
                    .bind("session_ids", json.encodeToString(strings, gate.sessionIds))
                    .bind("launch_ids", json.encodeToString(strings, gate.launchIds))
                    .bind("org_id", gate.orgId)
                    .bind("id", gate.id),
            ).getOrThrow()
        }

        override suspend fun updateGate(gate: Gate) {
            scoped(gate.orgId)
            executor.execute(
                Statement.create(
                    "UPDATE gates_gates SET gate_key = :gate_key, subject = :subject, node = :node, label = :label, " +
                        "mode = :mode, status = :status, asked_by = :asked_by, asked_via = :asked_via, " +
                        "session_ids = :session_ids, launch_ids = :launch_ids, shown_digest = :shown_digest, " +
                        "evidence = :evidence, " +
                        "decided_by = :decided_by, decided_at = :decided_at, self_approved = :self_approved, " +
                        "receipt_id = :receipt_id, reserved_by = :reserved_by, reserved_at = :reserved_at, " +
                        "consumed_at = :consumed_at, created_at = :created_at WHERE org_id = :org_id AND id = :id",
                ).bindGate(gate),
            ).getOrThrow()
        }

        override suspend fun insertRequests(requests: List<ApprovalRequest>) {
            requests.forEachIndexed { position, request ->
                scoped(request.orgId)
                executor.execute(
                    Statement.create(
                        "INSERT INTO gates_requests (id, gate_id, org_id, position, approver_id, status, " +
                            "answered_at, channel, created_at) VALUES (:id, :gate_id, :org_id, :position, " +
                            ":approver_id, :status, :answered_at, :channel, :created_at)",
                    ).bindRequest(request).bind("position", position),
                ).getOrThrow()
            }
        }

        override suspend fun updateRequests(requests: List<ApprovalRequest>) {
            for (request in requests) {
                scoped(request.orgId)
                executor.execute(
                    Statement.create(
                        "UPDATE gates_requests SET status = :status, answered_at = :answered_at, channel = :channel " +
                            "WHERE org_id = :org_id AND id = :id",
                    ).bind("org_id", request.orgId).bind("id", request.id).bind("status", request.status.name)
                        // sqlx4k refuses a bound name the statement does not use: only this update's own.
                        .bindOrNull("answered_at", request.answeredAt, Instant::class)
                        .bind("channel", request.channel?.name),
                ).getOrThrow()
            }
        }
    }

    private companion object {
        const val MAX_REQUESTS = 100
        const val GATE_COLUMNS = "id, org_id, gate_key, subject, node, label, mode, status, asked_by, asked_via, " +
            "session_ids, launch_ids, shown_digest, evidence, decided_by, decided_at, self_approved, receipt_id, " +
            "reserved_by, reserved_at, consumed_at, created_at"
        const val REQUEST_COLUMNS =
            "id, gate_id, org_id, position, approver_id, status, answered_at, channel, created_at"
        val evidences = ListSerializer(EvidenceRecord.serializer())
        val json = Json { ignoreUnknownKeys = true }
        val strings = ListSerializer(String.serializer())

        suspend fun QueryExecutor.rows(statement: Statement): List<ResultSet.Row> =
            fetchAll(statement).getOrThrow().toList()

        suspend fun QueryExecutor.selectGate(orgId: Uuid, id: Uuid, lock: Boolean): Gate? = rows(
            Statement.create(
                "SELECT $GATE_COLUMNS FROM gates_gates WHERE org_id = :org AND id = :id" +
                    if (lock) " FOR UPDATE" else "",
            ).bind("org", orgId).bind("id", id),
        ).firstOrNull()?.toGate()

        // A null for a typed column (uuid, timestamptz) is bound with its type, or PostgreSQL takes it for text.
        fun Statement.bindOrNull(name: String, value: Any?, type: KClass<*>): Statement =
            if (value == null) bindNull(name, type) else bind(name, value)

        fun Statement.bindGate(gate: Gate): Statement = bind("id", gate.id)
            .bind("org_id", gate.orgId)
            .bind("gate_key", gate.key)
            .bind("subject", json.encodeToString(SubjectRecord.serializer(), SubjectRecord.of(gate.subject)))
            .bind("node", gate.node?.let { json.encodeToString(NodeRecord.serializer(), NodeRecord.of(it)) })
            .bind("label", gate.label)
            .bind("mode", gate.mode.name)
            .bind("status", gate.status.name)
            .bind("asked_by", json.encodeToString(strings, gate.askedBy))
            .bind("asked_via", json.encodeToString(strings, gate.askedVia.map { it.name }.sorted()))
            .bind("session_ids", json.encodeToString(strings, gate.sessionIds))
            .bind("launch_ids", json.encodeToString(strings, gate.launchIds))
            .bind("shown_digest", gate.shownDigest)
            .bind("evidence", json.encodeToString(evidences, gate.evidence.map { EvidenceRecord(it.label, it.value) }))
            .bind("decided_by", gate.decidedBy)
            .bindOrNull("decided_at", gate.decidedAt, Instant::class)
            .bind("self_approved", gate.selfApproved)
            .bind("receipt_id", gate.receiptId)
            .bindOrNull("reserved_by", gate.reservedBy, String::class)
            .bindOrNull("reserved_at", gate.reservedAt, Instant::class)
            .bindOrNull("consumed_at", gate.consumedAt, Instant::class)
            .bind("created_at", gate.createdAt)

        fun Statement.bindRequest(request: ApprovalRequest): Statement = bind("id", request.id)
            .bind("gate_id", request.gateId)
            .bind("org_id", request.orgId)
            .bind("approver_id", request.approverId)
            .bind("status", request.status.name)
            .bindOrNull("answered_at", request.answeredAt, Instant::class)
            .bind("channel", request.channel?.name)
            .bind("created_at", request.createdAt)

        fun ResultSet.Row.toGate() = Gate(
            id = get("id").asUuid(),
            orgId = get("org_id").asUuid(),
            key = get("gate_key").asString(),
            subject = json.decodeFromString(SubjectRecord.serializer(), get("subject").asString()).toSubject(),
            node = get("node").asStringOrNull()?.let { json.decodeFromString(NodeRecord.serializer(), it).toNode() },
            label = get("label").asString(),
            mode = GateMode.valueOf(get("mode").asString()),
            status = GateStatus.valueOf(get("status").asString()),
            askedBy = json.decodeFromString(strings, get("asked_by").asString()),
            askedVia = json.decodeFromString(strings, get("asked_via").asString()).map { AskedVia.valueOf(it) }.toSet(),
            sessionIds = json.decodeFromString(strings, get("session_ids").asString()),
            launchIds = json.decodeFromString(strings, get("launch_ids").asString()),
            shownDigest = get("shown_digest").asString(),
            evidence = json.decodeFromString(evidences, get("evidence").asString())
                .map { Evidence(it.label, it.value) },
            decidedBy = get("decided_by").asStringOrNull(),
            decidedAt = get("decided_at").asInstantOrNull(),
            selfApproved = get("self_approved").asBoolean(),
            receiptId = get("receipt_id").asStringOrNull(),
            reservedBy = get("reserved_by").asStringOrNull(),
            reservedAt = get("reserved_at").asInstantOrNull(),
            consumedAt = get("consumed_at").asInstantOrNull(),
            createdAt = get("created_at").asInstant(),
        )

        fun ResultSet.Row.toRequest() = ApprovalRequest(
            id = get("id").asUuid(),
            gateId = get("gate_id").asUuid(),
            orgId = get("org_id").asUuid(),
            approverId = get("approver_id").asString(),
            status = RequestStatus.valueOf(get("status").asString()),
            answeredAt = get("answered_at").asInstantOrNull(),
            channel = get("channel").asStringOrNull()?.let { AnswerChannel.valueOf(it) },
            createdAt = get("created_at").asInstant(),
        )
    }
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
