// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.kernel.text.TranscriptMask
import run.ostrom.server.runners.domain.CloudConversationRules
import run.ostrom.server.runners.domain.CloudEventType
import run.ostrom.server.runners.domain.CloudInput
import run.ostrom.server.runners.domain.CloudInputKind
import run.ostrom.server.runners.domain.CloudInputStatus
import run.ostrom.server.runners.domain.PermissionRequestState
import run.ostrom.server.runners.domain.storedObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Instant
import kotlin.uuid.Uuid

// The writes both stores of the conversation make, in the caller's transaction, under the session row lock.

/** How many later `turn`, `input` or `permission.expired` events are read to find what closed a request. */
internal const val MAX_CLOSING_SCANNED = 200

/** Enough of a stored `permission.request` for its leading `requestId` (at most 128 characters, escaped). */
internal const val REQUEST_HEAD_CHARS = 300

/**
 * Final status for an input, with its event: a consumed message becomes its `message.user` event (who wrote what),
 * anything else an `input` event. The caller holds the session row lock.
 *
 * @return The input as it ended.
 */
internal fun finish(input: CloudInput, status: CloudInputStatus, reason: String?, now: Instant): CloudInput {
    val said = status == CloudInputStatus.CONSUMED && input.kind == CloudInputKind.MESSAGE
    val data = if (said) {
        buildJsonObject {
            put("inputId", input.id.toString())
            put("authorId", input.authorId)
            put("text", TranscriptMask.bound(input.text.orEmpty()))
            // Name, size and type only: never the path the host wrote the file to.
            if (input.files.isNotEmpty()) {
                put(
                    "files",
                    JsonArray(
                        input.files.map { file ->
                            buildJsonObject {
                                put("id", file.id.toString())
                                put("name", file.name)
                                put("size", file.size)
                                put("contentType", file.contentType)
                            }
                        },
                    ),
                )
            }
        }
    } else {
        buildJsonObject {
            put("inputId", input.id.toString())
            put("kind", input.kind.wire)
            put("status", status.wire)
            reason?.let { put("reason", it) }
            if (input.kind == CloudInputKind.PERMISSION) {
                // Who answered which request, and how; the answer's texts stay in the input.
                put("authorId", input.authorId)
                val answer = storedObject(input.text)
                answer.str("requestId")?.let { put("requestId", it) }
                answer.str("behavior")?.let { put("behavior", it) }
            }
        }
    }
    val type = if (said) CloudEventType.USER_MESSAGE else CloudEventType.INPUT
    val seq = serverEvent(input.orgId, input.sessionId, type, data.toString(), now)
    val consumed = if (status == CloudInputStatus.CONSUMED) now else input.consumedAt
    val t = RunnersSessionInputs
    t.update({ (t.orgId eq input.orgId) and (t.id eq input.id) }) {
        it[t.status] = status.wire
        it[finishedAt] = now
        it[consumedAt] = consumed
        it[t.reason] = reason
        it[eventSeq] = seq
        it[wakePending] = false
    }
    // Whatever the outcome, the message's files are no longer needed: the sweep removes their bytes.
    if (input.kind == CloudInputKind.MESSAGE) {
        releaseFiles(input.orgId, input.id, status == CloudInputStatus.CONSUMED, now)
    }
    return input.copy(status = status, finishedAt = now, consumedAt = consumed, reason = reason, eventSeq = seq)
}

/** A server-written event (epoch 0); the caller holds the session row lock. @return Its sequence number. */
internal fun serverEvent(orgId: Uuid, sessionId: Uuid, type: CloudEventType, data: String, now: Instant): Long {
    val c = RunnersSessionTranscripts
    val cursor = c.select(c.columns).where { (c.orgId eq orgId) and (c.sessionId eq sessionId) }.singleOrNull()
    val seq = (cursor?.get(c.lastSeq) ?: 0L) + 1
    insertEvent(orgId, sessionId, seq, 0, null, type, now, data, "")
    if (cursor != null) {
        c.update({ (c.orgId eq orgId) and (c.sessionId eq sessionId) }) { it[lastSeq] = seq }
    } else {
        c.insert {
            it[c.sessionId] = sessionId
            it[c.orgId] = orgId
            it[lastSeq] = seq
            it[runnerEpoch] = 0
            it[runnerSeq] = 0
        }
    }
    return seq
}

/** One event row; the caller holds the session row lock and keeps the transcript's cursor. */
@Suppress("LongParameterList") // The event's columns.
internal fun insertEvent(
    orgId: Uuid,
    sessionId: Uuid,
    seq: Long,
    epoch: Long,
    runnerSeq: Long?,
    type: CloudEventType,
    at: Instant,
    data: String,
    feed: String,
) {
    val e = RunnersSessionEvents
    e.insert {
        it[e.sessionId] = sessionId
        it[e.seq] = seq
        it[e.orgId] = orgId
        it[e.epoch] = epoch
        it[e.runnerSeq] = runnerSeq
        it[e.type] = type.wire
        it[e.at] = at
        it[e.data] = data
        it[e.feed] = feed
    }
}

/** Whether the request stored at [anchor] is still open: the first closing event after it. */
internal fun requestState(orgId: Uuid, sessionId: Uuid, anchor: Long, requestId: String): PermissionRequestState {
    val e = RunnersSessionEvents
    fun closing(type: CloudEventType, closes: (JsonObject?) -> Boolean): Long? =
        e.select(e.seq, e.data).where {
            (e.orgId eq orgId) and (e.sessionId eq sessionId) and (e.type eq type.wire) and (e.seq greater anchor)
        }.orderBy(e.seq to SortOrder.ASC).limit(MAX_CLOSING_SCANNED)
            .firstOrNull { row -> closes(storedObject(row[e.data])) }?.get(e.seq)
    // The request's turn ends at the first turn event after it that is an end; the oldest closing event wins.
    val ended = closing(CloudEventType.TURN) { it.str("phase") in CloudConversationRules.TURN_END_PHASES }
    val answered = closing(CloudEventType.INPUT) {
        (it.str("requestId") to it.str("status")) == (requestId to CloudInputStatus.CONSUMED.wire)
    }
    val expired = closing(CloudEventType.PERMISSION_EXPIRED) { it.str("requestId") == requestId }
    return listOfNotNull(
        ended?.let { it to PermissionRequestState.ENDED },
        answered?.let { it to PermissionRequestState.ANSWERED },
        expired?.let { it to PermissionRequestState.EXPIRED },
        // Nothing closed it: still open.
        Long.MAX_VALUE to PermissionRequestState.OPEN,
    ).sortedBy { it.first }.first().second
}
