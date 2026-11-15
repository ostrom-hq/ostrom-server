// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.runners.domain.CloudConversationRules
import run.ostrom.server.runners.domain.CloudEventType
import run.ostrom.server.runners.domain.CloudHumanWait
import run.ostrom.server.runners.domain.CloudInput
import run.ostrom.server.runners.domain.CloudInputKind
import run.ostrom.server.runners.domain.CloudInputStatus
import run.ostrom.server.runners.domain.CloudSessionNotFoundException
import run.ostrom.server.runners.domain.ConversationResume
import run.ostrom.server.runners.domain.DesiredState
import run.ostrom.server.runners.domain.PermissionRequestState
import run.ostrom.server.runners.domain.RunnerConversationStore
import run.ostrom.server.runners.domain.RunnerEvent
import run.ostrom.server.runners.domain.RunnerEventsAck
import run.ostrom.server.runners.domain.RunnerInputAck
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.runners.domain.TurnMarker
import run.ostrom.server.runners.domain.storedObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.substring
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [RunnerConversationStore] on PostgreSQL through Exposed (TeamCode's `SqlCloudConversationStore`, its runner side).
 * Every call runs in its organization ([JdbcDatabase.inOrg]) and locks the session row first (`FOR UPDATE`).
 *
 * @param database The JDBC pool.
 * @param clock The time the rows are written at.
 */
internal class RunnerConversationStoreImpl(
    private val database: JdbcDatabase,
    private val clock: Clock,
) : RunnerConversationStore {
    override suspend fun appendRunnerEvents(
        orgId: Uuid,
        sessionId: Uuid,
        epoch: Long,
        events: List<RunnerEvent>,
    ): RunnerEventsAck = database.inOrg(orgId) {
        val session = lock(orgId, sessionId)
        val c = RunnersSessionTranscripts
        val cursor = c.select(c.columns).where { (c.orgId eq orgId) and (c.sessionId eq sessionId) }.singleOrNull()
        // The cursor survives retention, so a long-lived execution keeps its numbering after old events are purged.
        val stored = cursor?.takeIf { it[c.runnerEpoch] == epoch }?.get(c.runnerSeq) ?: 0L
        val start = cursor?.get(c.lastSeq) ?: 0L
        // What an earlier try stored is skipped; a gap stops the batch: the runner resends from acceptedThrough + 1.
        val fresh = events.sortedBy { it.sequence }.filter { it.sequence > stored }
            .filterIndexed { index, event -> event.sequence == stored + 1 + index }
        val now = clock.now()
        val waited = readWait(session)
        val s = RunnersSessionsTable
        // A request raised while the session takes no answer (a pause asked: the runner denies it) never waits.
        val answerable = session[s.desiredState] == DesiredState.RUNNING.wire &&
            session[s.state] == SessionState.RUNNING.wire
        val subagents = SubagentTracker(orgId, sessionId, now)
        var wait = waited
        fresh.forEachIndexed { index, event ->
            val seq = start + 1 + index
            val data = storedObject(event.data)
            // Only these types carry a `parentToolUseId` or tell an agent's story.
            val feed = if (event.type in SUBAGENT_TYPES) subagents.see(event.type, data) else null
            insertEvent(orgId, sessionId, seq, epoch, event.sequence, event.type, now, event.data, feed.orEmpty())
            // The wait follows the transcript event by event, so it is exact whatever the batch holds.
            wait = when {
                event.type == CloudEventType.PERMISSION_REQUEST ->
                    humanWait(data, now, seq, epoch).takeIf { answerable } ?: wait
                // Every request of the turn ended with it.
                event.turn == TurnMarker.ENDED -> null
                event.type == CloudEventType.PERMISSION_EXPIRED ->
                    closeWait(orgId, sessionId, wait, data.str("requestId"))
                else -> wait
            }
        }
        val through = fresh.lastOrNull()?.sequence ?: stored
        val last = start + fresh.size
        if (fresh.isNotEmpty()) {
            saveCursor(orgId, sessionId, cursor != null, last, epoch, through)
            subagents.flush()
        }
        if (wait != waited) saveWait(orgId, sessionId, wait)
        RunnerEventsAck(through, last)
    }

    override suspend fun deliver(orgId: Uuid, sessionId: Uuid, epoch: Long, limit: Int): List<CloudInput> =
        database.inOrg(orgId) {
            lock(orgId, sessionId)
            val now = clock.now()
            retryStale(orgId, sessionId, epoch, now)
            val t = RunnersSessionInputs
            fun queued(urgent: Boolean, count: Int) = t.select(t.columns).where {
                (t.orgId eq orgId) and (t.sessionId eq sessionId) and (t.status eq CloudInputStatus.QUEUED.wire) and
                    (if (urgent) t.kind neq CloudInputKind.MESSAGE.wire else t.kind eq CloudInputKind.MESSAGE.wire)
            }.orderBy(t.createdAt to SortOrder.ASC, t.id to SortOrder.ASC).limit(count).map(::readInput)
            // Interrupts, stops, option changes and answers first, read on their own so a full message queue never
            // hides them: they concern the turn running now, not the queue behind it.
            val urgent = queued(urgent = true, limit)
            val chosen = if (urgent.size >= limit) urgent else urgent + queued(urgent = false, limit - urgent.size)
            val files = filesOf(orgId, chosen.filter { it.kind == CloudInputKind.MESSAGE }.map { it.id })
            chosen.map { it.copy(files = files[it.id].orEmpty()) }.map { input ->
                t.update({ (t.orgId eq orgId) and (t.id eq input.id) }) {
                    it[status] = CloudInputStatus.DELIVERED.wire
                    it[deliveredAt] = now
                    it[deliveredEpoch] = epoch
                    it[attempts] = input.attempts + 1
                }
                input.copy(
                    status = CloudInputStatus.DELIVERED,
                    deliveredAt = now,
                    deliveredEpoch = epoch,
                    attempts = input.attempts + 1,
                )
            }
        }

    /**
     * Deliveries never acknowledged: to an execution that ended, or to this one but lost on the way (a dropped
     * long-poll answer) or left alone while the agent stopped. A message is offered again, and a permission answer
     * to the same execution (the runner acknowledges a repeat without writing it twice), up to [CloudConversationRules
     * .MAX_ATTEMPTS] times; anything else is rejected, since it concerned the agent process it was delivered to.
     */
    private fun retryStale(orgId: Uuid, sessionId: Uuid, epoch: Long, now: Instant) {
        val t = RunnersSessionInputs
        val stale = t.select(t.columns).where {
            (t.orgId eq orgId) and (t.sessionId eq sessionId) and (t.status eq CloudInputStatus.DELIVERED.wire) and
                ((t.deliveredEpoch neq epoch) or (t.deliveredAt less (now - REDELIVERY)))
        }.map(::readInput)
        for (input in stale) {
            val sameExecution = epoch.equals(input.deliveredEpoch)
            val again = input.kind == CloudInputKind.MESSAGE ||
                (input.kind == CloudInputKind.PERMISSION && sameExecution)
            if (again && input.attempts < CloudConversationRules.MAX_ATTEMPTS) {
                t.update({ (t.orgId eq orgId) and (t.id eq input.id) }) { it[status] = CloudInputStatus.QUEUED.wire }
            } else {
                val ended = input.kind != CloudInputKind.MESSAGE && !sameExecution
                finish(input, CloudInputStatus.REJECTED, if (ended) "execution_ended" else "delivery_failed", now)
            }
        }
    }

    override suspend fun acknowledge(
        orgId: Uuid,
        sessionId: Uuid,
        epoch: Long,
        acks: List<RunnerInputAck>,
    ): List<CloudInput> = database.inOrg(orgId) {
        val session = lock(orgId, sessionId)
        val now = clock.now()
        val unique = acks.distinctBy { it.id }
        val t = RunnersSessionInputs
        val matched = t.select(t.columns).where {
            (t.orgId eq orgId) and (t.sessionId eq sessionId) and (t.id inList unique.map { it.id }) and
                (t.status eq CloudInputStatus.DELIVERED.wire) and (t.deliveredEpoch eq epoch)
        }.map(::readInput).associateBy { it.id }
        // The files of every acknowledged message in one read: a consumed message's event names them.
        val files = filesOf(orgId, matched.values.filter { it.kind == CloudInputKind.MESSAGE }.map { it.id })
        val finished = unique.mapNotNull { ack ->
            matched[ack.id]?.let { found ->
                val input = found.copy(files = files[ack.id].orEmpty())
                if (ack.consumed) {
                    finish(input, CloudInputStatus.CONSUMED, null, now)
                } else {
                    finish(input, CloudInputStatus.REJECTED, ack.reason ?: "rejected", now)
                }
            }
        }
        // A consumed answer closes its request: the wait moves to the next request still open, if it was this one.
        val waited = readWait(session)
        val wait = finished.filter { it.kind == CloudInputKind.PERMISSION && it.status == CloudInputStatus.CONSUMED }
            .fold(waited) { current, answer ->
                closeWait(orgId, sessionId, current, storedObject(answer.text).str("requestId"))
            }
        if (wait != waited) saveWait(orgId, sessionId, wait)
        finished
    }

    /**
     * The newest `status` event that says whether the conversation can be resumed (TeamCode keeps it on the session;
     * here the transcript is the record, read through its index on (session, type, seq)). Past the retention, or
     * never said: [ConversationResume.NONE], so the next execution starts a fresh conversation.
     */
    override suspend fun conversation(orgId: Uuid, sessionId: Uuid): ConversationResume = database.inOrg(orgId) {
        val e = RunnersSessionEvents
        val said = e.select(e.data).where {
            (e.orgId eq orgId) and (e.sessionId eq sessionId) and (e.type eq CloudEventType.STATUS.wire)
        }.orderBy(e.seq to SortOrder.DESC).limit(MAX_STATUS_SCANNED)
            .firstNotNullOfOrNull { ConversationResume.parse(storedObject(it[e.data]).str("conversation")) }
        said ?: ConversationResume.NONE
    }

    private fun lock(orgId: Uuid, sessionId: Uuid): ResultRow {
        val s = RunnersSessionsTable
        return s.select(
            s.id, s.state, s.desiredState, s.waitingReason, s.waitingRequestId, s.waitingSince, s.waitingEventSeq,
            s.waitingEpoch,
        ).where { (s.orgId eq orgId) and (s.id eq sessionId) }.forUpdate().singleOrNull()
            ?: throw CloudSessionNotFoundException()
    }

    /**
     * The wait once the request [requestId] was answered or expired: unchanged unless it is the one shown, else the
     * newest earlier request of the same execution still open, or none. Requests are read newest first, at most
     * [MAX_OPEN_SCANNED], from their head only; the first one whose turn ended stops the search, since every earlier
     * one ended with that turn or before. Called under the session row lock, after the closing event was written.
     */
    private fun closeWait(orgId: Uuid, sessionId: Uuid, wait: CloudHumanWait?, requestId: String?): CloudHumanWait? {
        if (wait == null || wait.requestId != requestId) return wait
        val e = RunnersSessionEvents
        val scope = (e.orgId eq orgId) and (e.sessionId eq sessionId)
        val head = e.data.substring(1, REQUEST_HEAD_CHARS)
        val earlier = e.select(e.seq, e.epoch, e.at, head).where {
            scope and (e.type eq CloudEventType.PERMISSION_REQUEST.wire) and (e.seq less wait.eventSeq)
        }.orderBy(e.seq to SortOrder.DESC).limit(MAX_OPEN_SCANNED).toList()
        // Another execution's requests ended with it; an id no member could answer with was denied at once.
        // Answered and expired ones are passed; the first open one waits, the first whose turn ended stops.
        val (row, state) = earlier.asSequence().takeWhile { it[e.epoch] == wait.epoch }
            .mapNotNull { row ->
                requestHeadId(row[head])?.let { id -> row to requestState(orgId, sessionId, row[e.seq], id) }
            }
            .firstOrNull { (_, state) -> state == PermissionRequestState.OPEN || state == PermissionRequestState.ENDED }
            ?: (null to PermissionRequestState.ENDED)
        return row?.takeIf { state == PermissionRequestState.OPEN }?.let { open ->
            val asked = e.select(e.data).where { scope and (e.seq eq open[e.seq]) }.single()
            humanWait(storedObject(asked[e.data]), open[e.at], open[e.seq], wait.epoch)
        }
    }

    private fun readWait(session: ResultRow): CloudHumanWait? {
        val s = RunnersSessionsTable
        val reason = session[s.waitingReason] ?: return null
        return CloudHumanWait(
            reason,
            session[s.waitingRequestId].orEmpty(),
            session[s.waitingSince] ?: Instant.DISTANT_PAST,
            session[s.waitingEventSeq] ?: 0L,
            session[s.waitingEpoch] ?: 0L,
        )
    }

    private fun saveWait(orgId: Uuid, sessionId: Uuid, wait: CloudHumanWait?) {
        val s = RunnersSessionsTable
        s.update({ (s.orgId eq orgId) and (s.id eq sessionId) }) {
            it[waitingReason] = wait?.reason
            it[waitingRequestId] = wait?.requestId
            it[waitingSince] = wait?.since
            it[waitingEventSeq] = wait?.eventSeq
            it[waitingEpoch] = wait?.epoch
        }
    }

    @Suppress("LongParameterList") // The cursor's columns.
    private fun saveCursor(orgId: Uuid, sessionId: Uuid, exists: Boolean, last: Long, epoch: Long, through: Long) {
        val c = RunnersSessionTranscripts
        if (exists) {
            c.update({ (c.orgId eq orgId) and (c.sessionId eq sessionId) }) {
                it[lastSeq] = last
                it[runnerEpoch] = epoch
                it[runnerSeq] = through
            }
        } else {
            c.insert {
                it[c.sessionId] = sessionId
                it[c.orgId] = orgId
                it[lastSeq] = last
                it[runnerEpoch] = epoch
                it[runnerSeq] = through
            }
        }
    }

    private companion object {
        /** Earlier requests read when the shown one closes: more than a turn holds open at once in practice. */
        const val MAX_OPEN_SCANNED = 16

        /** The newest `status` events read for the conversation's marker: the runner sends few of them. */
        const val MAX_STATUS_SCANNED = 50
    }
}

private val REDELIVERY = CloudConversationRules.REDELIVERY_MILLIS.milliseconds

/** Only these types carry a `parentToolUseId` or tell an agent's story. */
private val SUBAGENT_TYPES = setOf(
    CloudEventType.TOOL_CALL,
    CloudEventType.TOOL_RESULT,
    CloudEventType.AGENT_MESSAGE,
    CloudEventType.AGENT_MESSAGE_DELTA,
    CloudEventType.TASKS,
)

/** The wait a `permission.request` opens: a tool use to approve, or a question; none without an answerable id. */
private fun humanWait(request: JsonObject?, now: Instant, seq: Long, epoch: Long): CloudHumanWait? {
    val requestId = request.str("requestId")?.takeIf(CloudConversationRules::isPermissionRequestId) ?: return null
    val reason = if (request.str("question") == "true") "question" else "permission"
    return CloudHumanWait(reason, requestId, now, seq, epoch)
}

private val REQUEST_HEAD_ID = Regex("""^\{"requestId":("(?:[^"\\]|\\.)*")""")

/** The `requestId` a stored request starts with (the runner writes it first), or null. */
private fun requestHeadId(head: String): String? {
    val match = REQUEST_HEAD_ID.find(head) ?: return null
    val id = (Json.parseToJsonElement(match.groupValues[1]) as JsonPrimitive).content
    return id.takeIf(CloudConversationRules::isPermissionRequestId)
}
