// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.gates.infrastructure

import run.ostrom.server.gates.domain.models.AnswerChannel
import run.ostrom.server.gates.domain.models.ApprovalRequest
import run.ostrom.server.gates.domain.models.ApprovalView
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
import run.ostrom.server.gates.domain.repositories.GateHistory
import run.ostrom.server.gates.domain.repositories.GateSessionEnds
import run.ostrom.server.gates.domain.repositories.GatesRepository
import run.ostrom.server.gates.domain.repositories.GatesTransaction
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asBoolean
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstantOrNull
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLongOrNull
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.reflect.KClass
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [GatesRepository] and [GateHistory] on PostgreSQL (sqlx4k). Every call is one transaction that first sets
 * `ostrom.org_id`, which the row level security policies of the gates tables read: a query can never see or write
 * another organization's rows, even if its `WHERE` clause forgot the organization.
 *
 * @param driver The connection pool.
 */
class PostgresGatesRepository(private val driver: Driver) : GatesRepository, GateHistory {
    override suspend fun <T> transaction(block: suspend GatesTransaction.() -> T): T = driver.transaction {
        Tx(this).block()
    }

    override suspend fun get(orgId: Uuid, id: Uuid): Gate? = driver.transaction {
        Tx(this).run { scope.enter(orgId); executor.selectGate(orgId, id, lock = false) }
    }

    override suspend fun requestsOf(orgId: Uuid, gateId: Uuid): List<ApprovalRequest> = driver.transaction {
        Tx(this).requestsOf(orgId, gateId)
    }

    override suspend fun requestsOfGates(orgId: Uuid, gateIds: List<Uuid>): List<ApprovalRequest> =
        driver.transaction { Tx(this).requestsOfGates(orgId, gateIds) }

    override suspend fun ofSession(orgId: Uuid, sessionId: String): List<Gate> = driver.transaction {
        Tx(this).gatesOfSession(orgId, sessionId)
    }

    override suspend fun recent(orgId: Uuid, since: Instant, limit: Int): List<Gate> = driver.transaction {
        Tx(this).run {
            scope.enter(orgId)
            executor.rows(
                Statement.create(GatesQueries.RECENT).bind("org", orgId).bind("since", since).bind("limit", limit),
            ).map { it.toGate() }
        }
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
        before: Uuid?,
    ): List<ApprovalView> = driver.transaction {
        Tx(this).run {
            scope.enter(orgId)
            val page = Statement.create(GatesQueries.approvals(approverId != null, status != null, before != null))
                .bind("org", orgId).bind("limit", limit)
            approverId?.let { page.bind("approver", it) }
            status?.let { page.bind("status", it.name) }
            before?.let { page.bind("before", it) }
            val requests = executor.rows(page).map { it.toRequest() }
            val gateIds = requests.map { it.gateId }.distinct()
            // One read for every gate of the page (at most `limit` ids, each bound): no query per request.
            val gates = if (gateIds.isEmpty()) {
                emptyMap()
            } else {
                val statement = Statement.create(GatesQueries.gates(gateIds.size)).bind("org", orgId)
                gateIds.forEachIndexed { index, gateId -> statement.bind("g$index", gateId) }
                executor.rows(statement).map { it.toGate() }.associateBy { it.id }
            }
            // Every request has its gate (foreign key, gates are never deleted).
            requests.map { ApprovalView(it, gates.getValue(it.gateId)) }
        }
    }

    override suspend fun sessionsApprovedBy(orgId: Uuid, approver: String, sessionId: String?): List<String> =
        driver.transaction {
            Tx(this).run {
                scope.enter(orgId)
                val statement = if (sessionId == null) {
                    Statement.create(GatesQueries.APPROVED_SESSIONS)
                } else {
                    Statement.create(GatesQueries.APPROVES).bind("session", sessionId)
                }
                executor.rows(statement.bind("org", orgId).bind("approver", approver))
                    .map { it.get("session_id").asString() }
            }
        }

    override suspend fun approversOf(orgId: Uuid, gateIds: List<Uuid>): Map<Uuid, List<String>> {
        if (gateIds.isEmpty()) return emptyMap()
        return driver.transaction {
            Tx(this).run {
                scope.enter(orgId)
                // Each id bound on its own (`:g0`, `:g1`…), as the gates of a page above; gates_requests_by_gate.
                val statement = Statement.create(approversSql(gateIds.size)).bind("org", orgId)
                gateIds.forEachIndexed { index, gateId -> statement.bind("g$index", gateId) }
                executor.rows(statement).groupBy(
                    keySelector = { it.get("gate_id").asUuid() },
                    valueTransform = { it.get("approver_id").asString() },
                )
            }
        }
    }

    // The organization the transaction's row-level security is set to, set once per organization.
    private class OrgScope(private val executor: QueryExecutor) {
        private var org: Uuid? = null

        suspend fun enter(orgId: Uuid) {
            if (org == orgId) return
            executor.fetchAll(
                Statement.create("SELECT set_config('ostrom.org_id', :org, TRUE)").bind("org", orgId.toString()),
            ).getOrThrow()
            org = orgId
        }
    }

    // The sessions' ends apart ([SessionEnds]): one class would hold more functions than detekt allows.
    private class Tx private constructor(
        val executor: QueryExecutor,
        val scope: OrgScope,
    ) : GatesTransaction, GateSessionEnds by SessionEnds(executor, scope) {
        constructor(executor: QueryExecutor) : this(executor, OrgScope(executor))


        override suspend fun lockKey(orgId: Uuid, key: String) {
            scope.enter(orgId)
            executor.fetchAll(
                Statement.create("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))").bind("key", key),
            ).getOrThrow()
        }

        override suspend fun byKey(orgId: Uuid, key: String, limit: Int): List<Gate> {
            scope.enter(orgId)
            return executor.rows(
                Statement.create(GatesQueries.BY_KEY).bind("org", orgId).bind("key", key).bind("limit", limit),
            ).map { it.toGate() }
        }

        override suspend fun lockGate(orgId: Uuid, id: Uuid): Gate? {
            scope.enter(orgId)
            return executor.selectGate(orgId, id, lock = true)
        }

        override suspend fun request(orgId: Uuid, id: Uuid): ApprovalRequest? {
            scope.enter(orgId)
            return executor.rows(
                Statement.create(GatesQueries.REQUEST).bind("org", orgId).bind("id", id),
            ).firstOrNull()?.toRequest()
        }

        override suspend fun requestsOf(orgId: Uuid, gateId: Uuid): List<ApprovalRequest> {
            scope.enter(orgId)
            return executor.rows(
                Statement.create(GatesQueries.REQUESTS_OF).bind("org", orgId).bind("gate", gateId),
            ).map { it.toRequest() }
        }

        override suspend fun insertGate(gate: Gate) {
            scope.enter(gate.orgId)
            executor.execute(
                Statement.create(
                    "INSERT INTO gates_gates (${GatesQueries.GATE_COLUMNS}) " +
                        "VALUES (:id, :org_id, :gate_key, :subject, :node, " +
                        ":label, :mode, :status, :asked_by, :asked_via, :session_ids, :launch_ids, :shown_digest, " +
                        ":evidence, :decided_by, :decided_at, :self_approved, :receipt_id, :reserved_by, " +
                        ":reserved_at, :consumed_at, :released_at, :created_at, :unlinked_author)",
                ).bindGate(gate),
            ).getOrThrow()
            executor.linkSessions(gate)
        }

        override suspend fun addAskers(gate: Gate) {
            scope.enter(gate.orgId)
            executor.execute(
                Statement.create(
                    "UPDATE gates_gates SET asked_by = :asked_by, asked_via = :asked_via, " +
                        "session_ids = :session_ids, launch_ids = :launch_ids, unlinked_author = :unlinked_author " +
                        "WHERE org_id = :org_id AND id = :id",
                ).bind("asked_by", json.encodeToString(strings, gate.askedBy))
                    .bind("asked_via", json.encodeToString(strings, gate.askedVia.map { it.name }.sorted()))
                    .bind("session_ids", json.encodeToString(strings, gate.sessionIds))
                    .bind("launch_ids", json.encodeToString(strings, gate.launchIds))
                    .bindOrNull("unlinked_author", gate.unlinkedAuthor, Long::class)
                    .bind("org_id", gate.orgId)
                    .bind("id", gate.id),
            ).getOrThrow()
            executor.linkSessions(gate)
        }

        override suspend fun updateGate(gate: Gate) {
            scope.enter(gate.orgId)
            executor.execute(
                Statement.create(
                    "UPDATE gates_gates SET gate_key = :gate_key, subject = :subject, node = :node, label = :label, " +
                        "mode = :mode, status = :status, asked_by = :asked_by, asked_via = :asked_via, " +
                        "session_ids = :session_ids, launch_ids = :launch_ids, shown_digest = :shown_digest, " +
                        "evidence = :evidence, " +
                        "decided_by = :decided_by, decided_at = :decided_at, self_approved = :self_approved, " +
                        "receipt_id = :receipt_id, reserved_by = :reserved_by, reserved_at = :reserved_at, " +
                        "consumed_at = :consumed_at, released_at = :released_at, created_at = :created_at, " +
                        "unlinked_author = :unlinked_author " +
                        "WHERE org_id = :org_id AND id = :id",
                ).bindGate(gate),
            ).getOrThrow()
        }

        override suspend fun insertRequests(requests: List<ApprovalRequest>) {
            requests.forEachIndexed { position, request ->
                scope.enter(request.orgId)
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
                scope.enter(request.orgId)
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

    // AUDIT-SERVEUR-3 B2: the lock and the mark of a session's end, and the reads of its receipt.
    private class SessionEnds(val executor: QueryExecutor, val scope: OrgScope) : GateSessionEnds {
        // The sessions module's motif (`sessions:<org>:<root>`), in the gates' own name.
        override suspend fun lockSession(orgId: Uuid, sessionId: String) {
            executor.fetchAll(
                Statement.create("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                    .bind("key", "gates-session:$orgId:$sessionId"),
            ).getOrThrow()
        }

        override suspend fun sessionEndedAt(orgId: Uuid, sessionId: String): Instant? {
            scope.enter(orgId)
            return executor.rows(
                Statement.create(GatesQueries.SESSION_ENDED).bind("org", orgId).bind("session", sessionId),
            ).firstOrNull()?.let { it.get("ended_at").asInstant() }
        }

        override suspend fun markSessionEnded(orgId: Uuid, sessionId: String, at: Instant) {
            scope.enter(orgId)
            executor.execute(
                Statement.create(GatesQueries.MARK_SESSION_ENDED).bind("org", orgId).bind("session", sessionId)
                    .bind("at", at),
            ).getOrThrow()
        }

        // Served by the primary key of the session links (migration 3).
        override suspend fun gatesOfSession(orgId: Uuid, sessionId: String): List<Gate> {
            scope.enter(orgId)
            return executor.rows(
                Statement.create(GatesQueries.OF_SESSION).bind("org", orgId).bind("session", sessionId),
            ).map { it.toGate() }
        }

        override suspend fun requestsOfGates(orgId: Uuid, gateIds: List<Uuid>): List<ApprovalRequest> {
            scope.enter(orgId)
            val statement = Statement.create(GatesQueries.requestsOf(gateIds.size)).bind("org", orgId)
            gateIds.forEachIndexed { index, gateId -> statement.bind("g$index", gateId) }
            return executor.rows(statement).map { it.toRequest() }
        }
    }

    private companion object {
        val evidences = ListSerializer(EvidenceRecord.serializer())
        val json = Json { ignoreUnknownKeys = true }
        val strings = ListSerializer(String.serializer())

        // A gate's sessions are set when it opens and only grow when another session joins it (`addAskers`).
        suspend fun QueryExecutor.linkSessions(gate: Gate) {
            if (gate.sessionIds.isEmpty()) return
            val statement = Statement.create(GatesQueries.linkSessions(gate.sessionIds.size))
                .bind("org", gate.orgId).bind("gate", gate.id)
            gate.sessionIds.forEachIndexed { index, session -> statement.bind("s$index", session) }
            execute(statement).getOrThrow()
        }

        suspend fun QueryExecutor.rows(statement: Statement): List<ResultSet.Row> =
            fetchAll(statement).getOrThrow().toList()

        suspend fun QueryExecutor.selectGate(orgId: Uuid, id: Uuid, lock: Boolean): Gate? = rows(
            Statement.create(GatesQueries.gate(lock)).bind("org", orgId).bind("id", id),
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
            .bindOrNull("released_at", gate.releasedAt, Instant::class)
            .bind("created_at", gate.createdAt)
            .bindOrNull("unlinked_author", gate.unlinkedAuthor, Long::class)

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
            releasedAt = get("released_at").asInstantOrNull(),
            createdAt = get("created_at").asInstant(),
            unlinkedAuthor = get("unlinked_author").asLongOrNull(),
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

/**
 * The read of [PostgresGatesRepository.approversOf]: the approvers of [count] gates, `:org` and `:g0`…`:g<count-1>`
 * bound, at most [APPROVERS_PER_GATE] each as when a gate's requests are read. The cap is per gate, never one limit
 * for all of them: a gate with many requests cannot push the next gates out of the result. Shared with its plan test.
 *
 * @param count How many gate ids are bound.
 * @return The statement's text.
 */
internal fun approversSql(count: Int): String {
    val names = (0 until count).joinToString(", ") { ":g$it" }
    return "SELECT gate_id, approver_id FROM (SELECT gate_id, approver_id, position, " +
        "row_number() OVER (PARTITION BY gate_id ORDER BY position) AS rank FROM gates_requests " +
        "WHERE org_id = :org AND gate_id IN ($names)) ranked " +
        "WHERE rank <= $APPROVERS_PER_GATE ORDER BY gate_id, position"
}

/** How many requests a gate's read returns at most. */
private const val APPROVERS_PER_GATE = 100
