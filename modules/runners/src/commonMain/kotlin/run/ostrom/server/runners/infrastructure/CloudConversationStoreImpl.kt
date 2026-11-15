// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.runners.domain.CloudConversationException
import run.ostrom.server.runners.domain.AnswerContext
import run.ostrom.server.runners.domain.CloudConversationRules
import run.ostrom.server.runners.domain.CloudConversationStore
import run.ostrom.server.runners.domain.CloudEvent
import run.ostrom.server.runners.domain.CloudEventPage
import run.ostrom.server.runners.domain.CloudEventType
import run.ostrom.server.runners.domain.CloudInput
import run.ostrom.server.runners.domain.CloudInputKind
import run.ostrom.server.runners.domain.CloudInputPage
import run.ostrom.server.runners.domain.CloudInputStatus
import run.ostrom.server.runners.domain.storedObject
import run.ostrom.server.runners.domain.CloudSessionNotFoundException
import run.ostrom.server.runners.domain.CloudSubagent
import run.ostrom.server.runners.domain.DesiredState
import run.ostrom.server.runners.domain.InputOutcome
import run.ostrom.server.runners.domain.NewInput
import run.ostrom.server.runners.domain.PendingWake
import run.ostrom.server.runners.domain.PermissionBypass
import run.ostrom.server.runners.domain.PermissionRequestRecord
import run.ostrom.server.runners.domain.PermissionRequestState
import run.ostrom.server.runners.domain.QuestionShape
import run.ostrom.server.runners.domain.SessionState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.core.substring
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [CloudConversationStore] on PostgreSQL through Exposed (TeamCode's `SqlCloudConversationStore`, tables of
 * [RunnersMigrations] version 16). Every query names its organization and runs in [JdbcDatabase.inOrg]; a write that
 * numbers events or counts inputs locks the session row first (`FOR UPDATE`), so replicas agree. What crosses
 * organizations (the purge, the wake sweep, the inputs of ended sessions) goes through the `ostrom_router` functions
 * of version 16, and is then done in each organization.
 *
 * @param database The JDBC pool.
 */
@Suppress("TooManyFunctions") // TeamCode's one store of the conversation's tables.
internal class CloudConversationStoreImpl(private val database: JdbcDatabase) : CloudConversationStore {
    override suspend fun events(
        orgId: Uuid,
        sessionId: Uuid,
        after: Long?,
        before: Long?,
        limit: Int,
        feed: String?,
    ): CloudEventPage = database.inOrg(orgId) {
        val e = RunnersSessionEvents
        val last = lastSeqOf(orgId, sessionId)
        // The index (session, feed, seq) serves one feed's pages; without a feed every event is one.
        val session = (e.orgId eq orgId) and (e.sessionId eq sessionId)
        val scope: Op<Boolean> = when (feed) {
            null -> session
            CloudConversationRules.MAIN_FEED -> session and (e.feed eq "")
            else -> session and (e.feed eq feed)
        }
        fun edge(order: SortOrder): Long? =
            e.select(e.seq).where { scope }.orderBy(e.seq to order).limit(1).singleOrNull()?.get(e.seq)
        val first = edge(SortOrder.ASC)
        // The newest event of the feed bounds `nextAfter`; the session's own newest does when no feed was asked.
        val newest = if (feed == null) last else edge(SortOrder.DESC) ?: 0L
        if (before != null) {
            val rows = e.select(e.columns).where { scope and (e.seq less before) }.orderBy(e.seq to SortOrder.DESC)
                .limit(limit + 1).toList()
            val page = rows.take(limit).map(::readEvent).reversed()
            CloudEventPage(
                page,
                first,
                last,
                page.lastOrNull()?.seq?.takeIf { it < newest },
                if (rows.size > limit) page.first().seq else null,
            )
        } else {
            val rows = e.select(e.columns).where { scope and (e.seq greater (after ?: 0L)) }
                .orderBy(e.seq to SortOrder.ASC).limit(limit + 1).toList()
            val page = rows.take(limit).map(::readEvent)
            CloudEventPage(
                page,
                first,
                last,
                if (rows.size > limit) page.last().seq else null,
                first?.let { oldest -> page.firstOrNull()?.seq?.takeIf { it > oldest } },
            )
        }
    }

    override suspend fun subagents(orgId: Uuid, sessionId: Uuid, limit: Int): List<CloudSubagent> =
        database.inOrg(orgId) {
            val s = RunnersSessionSubagents
            // The newest through (session, started_at), then oldest first for the reader.
            s.select(s.columns).where { (s.orgId eq orgId) and (s.sessionId eq sessionId) }
                .orderBy(s.startedAt to SortOrder.DESC).limit(limit).map {
                    CloudSubagent(
                        it[s.id], it[s.type], it[s.description], it[s.state], it[s.startedAt], it[s.endedAt],
                        it[s.steps], it[s.parentId], it[s.taskId],
                    )
                }.reversed()
        }

    override suspend fun lastSeq(orgId: Uuid, sessionId: Uuid): Long =
        database.inOrg(orgId) { lastSeqOf(orgId, sessionId) }

    override suspend fun insertInput(new: NewInput, pendingLimit: Int): InputOutcome = database.inOrg(new.input.orgId) {
        val input = new.input
        val session = lockSession(input.orgId, input.sessionId)
            ?: throw CloudSessionNotFoundException()
        // A retry of an input already stored is its first answer, even once the session ended.
        val replayed = replayOf(input)
        if (replayed != null) return@inOrg InputOutcome.Replayed(withFiles(replayed))
        val state = SessionState.fromWire(session[RunnersSessionsTable.state])
        val desired = DesiredState.fromWire(session[RunnersSessionsTable.desiredState])
        if (state.isFinal || desired == DesiredState.TERMINATED) {
            throw CloudConversationRules.conflict(
                "session_not_running",
                "The cloud session is terminated or being deleted.",
            )
        }
        val asked = new.permissionRequestId?.let { findPermissionRequest(input.orgId, input.sessionId, it) }
        new.permissionCheck?.invoke(AnswerContext(asked, state, desired, session[RunnersSessionsTable.epoch]))
        val expected = new.expectedLastEventSeq
        if (expected != null && lastSeqOf(input.orgId, input.sessionId) > expected) {
            throw CloudConversationRules.conflict(
                "stale_view",
                "The transcript has newer events than expectedLastEventSeq. Read them first.",
            )
        }
        checkPending(new, pendingLimit)
        val files = bindFiles(new, input.createdAt)
        val sleeping = desired == DesiredState.PAUSED || state == SessionState.PAUSING || state == SessionState.PAUSED
        insertRow(input, input.kind == CloudInputKind.MESSAGE && input.wake && sleeping)
        InputOutcome.Accepted(input.copy(files = files))
    }

    /**
     * The input first stored with this idempotency key; the caller holds the session row lock.
     *
     * @throws CloudConversationException `idempotency_conflict` when the key went with another request.
     */
    private fun replayOf(input: CloudInput): CloudInput? {
        val key = input.clientKey ?: return null
        val t = RunnersSessionInputs
        val existing = t.select(t.columns).where {
            (t.orgId eq input.orgId) and (t.sessionId eq input.sessionId) and (t.authorId eq input.authorId) and
                (t.clientKey eq key)
        }.singleOrNull()?.let(::readInput)
        if (existing != null && (existing.requestDigest != input.requestDigest || existing.kind != input.kind)) {
            throw idempotencyConflict()
        }
        return existing
    }

    /** One answer per request at a time, and the pending bounds, decided under the session lock. */
    private fun checkPending(new: NewInput, pendingLimit: Int) {
        val input = new.input
        // A permission answer has its own small bound: messages queued behind a waiting turn must not block it.
        val answering = input.kind == CloudInputKind.PERMISSION
        val pending = pendingOf(input.orgId, input.sessionId)
        val limit = if (answering) CloudConversationRules.MAX_PENDING_PERMISSION_ANSWERS else pendingLimit
        val requestId = new.permissionRequestId
        val onItsWay = pending.any { it.kind == CloudInputKind.PERMISSION && requestIdOf(it.text) == requestId }
        if (requestId != null && onItsWay) {
            throw CloudConversationRules.conflict(
                "permission_request_answered",
                "This permission request already has an answer on its way to the agent.",
            )
        }
        if (pending.count { (it.kind == CloudInputKind.PERMISSION) == answering } >= limit) {
            val what = if (answering) "permission answers" else "inputs"
            throw CloudConversationException(
                ErrorKind.RATE_LIMITED,
                "too_many_pending_inputs",
                "The session already has $limit pending $what. Wait for the agent to consume them.",
            )
        }
    }

    private fun insertRow(input: CloudInput, wakePending: Boolean) {
        val t = RunnersSessionInputs
        t.insert {
            it[id] = input.id
            it[sessionId] = input.sessionId
            it[orgId] = input.orgId
            it[kind] = input.kind.wire
            it[status] = input.status.wire
            it[authorId] = input.authorId
            it[credentialId] = input.credentialId
            it[text] = input.text
            it[wake] = input.wake
            it[t.wakePending] = wakePending
            it[clientKey] = input.clientKey
            it[requestDigest] = input.requestDigest
            it[createdAt] = input.createdAt
            it[attempts] = 0
        }
    }

    override suspend fun input(orgId: Uuid, sessionId: Uuid, id: Uuid): CloudInput? = database.inOrg(orgId) {
        val t = RunnersSessionInputs
        t.select(t.columns).where { (t.orgId eq orgId) and (t.sessionId eq sessionId) and (t.id eq id) }
            .singleOrNull()?.let { withFiles(readInput(it)) }
    }

    /** The input with the files it still carries (a received message's files are deleted soon after). */
    private fun withFiles(input: CloudInput): CloudInput =
        input.copy(files = filesOf(input.orgId, listOf(input.id))[input.id].orEmpty())

    override suspend fun inputs(orgId: Uuid, sessionId: Uuid, beforeId: Uuid?, limit: Int): CloudInputPage =
        database.inOrg(orgId) {
            val t = RunnersSessionInputs
            val scope = (t.orgId eq orgId) and (t.sessionId eq sessionId)
            var condition: Op<Boolean> = scope
            if (beforeId != null) {
                val known = t.select(t.createdAt).where { scope and (t.id eq beforeId) }.singleOrNull()
                    ?: throw CloudConversationRules.invalid("invalid_cursor", "Unknown input cursor.")
                val cursor = known[t.createdAt]
                val older = (t.createdAt less cursor) or ((t.createdAt eq cursor) and (t.id less beforeId))
                condition = condition and older
            }
            val rows = t.select(t.columns).where { condition }
                .orderBy(t.createdAt to SortOrder.DESC, t.id to SortOrder.DESC).limit(limit + 1).toList()
            val read = rows.take(limit).map(::readInput)
            val files = filesOf(orgId, read.filter { it.kind == CloudInputKind.MESSAGE }.map { it.id })
            val page = read.map { it.copy(files = files[it.id].orEmpty()) }
            CloudInputPage(page, if (rows.size > limit) page.last().id else null)
        }

    override suspend fun clearWake(orgId: Uuid, sessionId: Uuid) {
        database.inOrg(orgId) {
            val t = RunnersSessionInputs
            t.update({ (t.orgId eq orgId) and (t.sessionId eq sessionId) and (t.wakePending eq true) }) {
                it[wakePending] = false
            }
        }
    }

    override suspend fun pendingWakes(limit: Int): List<PendingWake> = database.query {
        val w = RunnersConversationWakes
        w.of(limit).select(w.sessionId, w.orgId, w.inputId).map { PendingWake(it[w.orgId], it[w.sessionId], it[w.inputId]) }
    }

    override suspend fun cancelEnded(limit: Int, now: Instant): Int {
        val ended = database.query {
            val e = RunnersConversationEnded
            e.of(limit).select(e.sessionId, e.orgId).map { it[e.orgId] to it[e.sessionId] }
        }
        var changed = 0
        for ((orgId, sessionId) in ended) {
            changed += database.inOrg(orgId) {
                // A session deleted since it was listed took its inputs with it: nothing is left to cancel.
                lockSession(orgId, sessionId)
                val pending = pendingOf(orgId, sessionId)
                pending.forEach { finish(it, CloudInputStatus.CANCELLED, "session_ended", now) }
                pending.size
            }
        }
        return changed
    }

    override suspend fun purge(before: Instant, limit: Int): Int =
        database.query { scalar(purgeConversation(before, limit)).toInt() }

    override suspend fun setPermissionBypass(
        orgId: Uuid,
        sessionId: Uuid,
        bypass: PermissionBypass?,
        now: Instant,
    ): Boolean = updateSession(orgId, sessionId, now) {
        it[RunnersSessionsTable.bypassBy] = bypass?.by
        it[RunnersSessionsTable.bypassCredentialId] = bypass?.credentialId
        it[RunnersSessionsTable.bypassAt] = bypass?.at
    }

    override suspend fun setAgentOption(
        orgId: Uuid,
        sessionId: Uuid,
        effort: Boolean,
        value: String,
        now: Instant,
    ): Boolean = updateSession(orgId, sessionId, now) {
        it[if (effort) RunnersSessionsTable.effort else RunnersSessionsTable.model] = value
    }

    /** One write of the session row, unless it ended; the generation moves, as every write of the row. */
    private suspend fun updateSession(
        orgId: Uuid,
        sessionId: Uuid,
        now: Instant,
        change: (UpdateBuilder<*>) -> Unit,
    ): Boolean = database.inOrg(orgId) {
        val s = RunnersSessionsTable
        s.update({
            (s.orgId eq orgId) and (s.id eq sessionId) and
                (s.state notInList FINAL_STATES) and (s.desiredState neq DesiredState.TERMINATED.wire)
        }) {
            change(it)
            it[s.generation] = s.generation + 1L
            it[s.updatedAt] = now
        } > 0
    }

    /**
     * Through the index (session, type, seq): the newest of the session's latest requests whose stored event starts
     * with this `requestId` (the runner writes it first, so only that head is read), then what closed it since.
     */
    private fun findPermissionRequest(orgId: Uuid, sessionId: Uuid, requestId: String): PermissionRequestRecord? {
        val e = RunnersSessionEvents
        val head = e.data.substring(1, REQUEST_HEAD_CHARS)
        val expected = "{\"requestId\":" + JsonPrimitive(requestId)
        val anchor = e.select(e.seq, head).where {
            (e.orgId eq orgId) and (e.sessionId eq sessionId) and (e.type eq CloudEventType.PERMISSION_REQUEST.wire)
        }.orderBy(e.seq to SortOrder.DESC).limit(MAX_REQUESTS_SCANNED).firstOrNull { row ->
            val rest = row[head].removePrefix(expected)
            rest.length < row[head].length && (rest.startsWith(",") || rest.startsWith("}"))
        }?.get(e.seq) ?: return null
        val asked = e.select(e.data, e.epoch)
            .where { (e.orgId eq orgId) and (e.sessionId eq sessionId) and (e.seq eq anchor) }.single()
        // A head that matched on data that is not JSON (cut by hand) names no request.
        return storedObject(asked[e.data])?.let { data ->
            permissionRequestRecord(requestId, data, requestState(orgId, sessionId, anchor, requestId))
                .copy(epoch = asked[e.epoch])
        }
    }

    /** Queued and delivered inputs of a session, oldest first; the caller holds the session row lock. */
    private fun pendingOf(orgId: Uuid, sessionId: Uuid): List<CloudInput> {
        val t = RunnersSessionInputs
        return t.select(t.columns).where {
            (t.orgId eq orgId) and (t.sessionId eq sessionId) and (t.status inList PENDING)
        }.orderBy(t.createdAt to SortOrder.ASC).limit(MAX_PENDING_ALL).map(::readInput)
    }

    private fun lockSession(orgId: Uuid, sessionId: Uuid): ResultRow? {
        val s = RunnersSessionsTable
        return s.select(s.id, s.state, s.desiredState, s.epoch)
            .where { (s.orgId eq orgId) and (s.id eq sessionId) }.forUpdate().singleOrNull()
    }

    private fun lastSeqOf(orgId: Uuid, sessionId: Uuid): Long {
        val c = RunnersSessionTranscripts
        return c.select(c.lastSeq).where { (c.orgId eq orgId) and (c.sessionId eq sessionId) }.singleOrNull()
            ?.get(c.lastSeq) ?: 0L
    }

    private companion object {
        /** How many of the session's latest requests an answer is matched against. */
        const val MAX_REQUESTS_SCANNED = 200

        /** Every input a session may hold pending: messages and interrupts, plus permission answers. */
        const val MAX_PENDING_ALL =
            CloudConversationRules.MAX_PENDING_INPUTS + CloudConversationRules.MAX_PENDING_PERMISSION_ANSWERS
    }
}

/** The statuses an input is still pending in, as stored. */
private val PENDING = CloudInputStatus.PENDING.map { it.wire }

/** The states a session has ended in, as stored. */
private val FINAL_STATES = listOf(SessionState.TERMINATED.wire, SessionState.FAILED.wire)

/** The same idempotency key with another request: TeamCode's `422 idempotency_conflict`. */
private fun idempotencyConflict() = CloudConversationException(
    ErrorKind.UNPROCESSABLE,
    "idempotency_conflict",
    "This idempotency key was already used with a different request.",
)

private fun readEvent(row: ResultRow): CloudEvent {
    val e = RunnersSessionEvents
    return CloudEvent(
        row[e.sessionId], row[e.seq], CloudEventType.parse(row[e.type]) ?: CloudEventType.STATUS, row[e.at],
        row[e.epoch], row[e.data], row[e.feed].takeIf { it.isNotEmpty() },
    )
}

/** An input as stored. */
internal fun readInput(row: ResultRow): CloudInput {
    val t = RunnersSessionInputs
    return CloudInput(
        id = row[t.id],
        orgId = row[t.orgId],
        sessionId = row[t.sessionId],
        kind = CloudInputKind.parse(row[t.kind]),
        status = CloudInputStatus.parse(row[t.status]),
        authorId = row[t.authorId],
        credentialId = row[t.credentialId],
        text = row[t.text],
        wake = row[t.wake],
        clientKey = row[t.clientKey],
        requestDigest = row[t.requestDigest],
        createdAt = row[t.createdAt],
        deliveredAt = row[t.deliveredAt],
        deliveredEpoch = row[t.deliveredEpoch],
        attempts = row[t.attempts],
        consumedAt = row[t.consumedAt],
        finishedAt = row[t.finishedAt],
        reason = row[t.reason],
        eventSeq = row[t.eventSeq],
    )
}

/** A string field of stored JSON, if it is one. */
internal fun JsonObject?.str(name: String): String? = (this?.get(name) as? JsonPrimitive)?.contentOrNull

private fun requestIdOf(text: String?): String? = storedObject(text).str("requestId")

/** A stored `permission.request`: whether it is a question, and each question's shape when its input was kept. */
private fun permissionRequestRecord(
    requestId: String,
    data: JsonObject,
    state: PermissionRequestState,
): PermissionRequestRecord {
    val questions = ((data["input"] as? JsonObject)?.get("questions") as? JsonArray)?.map { question ->
        val shape = question as? JsonObject
        QuestionShape(
            (shape?.get("options") as? JsonArray)?.size ?: 0,
            (shape?.get("multiSelect") as? JsonPrimitive)?.booleanOrNull == true,
            (shape?.get("question") as? JsonPrimitive)?.contentOrNull,
        )
    }
    val leftOut = (data["inputTruncated"] as? JsonPrimitive)?.booleanOrNull == true || data["input"] !is JsonObject
    return PermissionRequestRecord(
        requestId,
        (data["question"] as? JsonPrimitive)?.booleanOrNull == true,
        questions,
        state,
        inputLeftOut = leftOut,
    )
}
