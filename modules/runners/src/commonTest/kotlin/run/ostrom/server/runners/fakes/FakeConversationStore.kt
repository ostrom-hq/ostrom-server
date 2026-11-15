// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.fakes

import run.ostrom.server.runners.domain.AnswerContext
import run.ostrom.server.runners.domain.CloudConversationStore
import run.ostrom.server.runners.domain.CloudEvent
import run.ostrom.server.runners.domain.CloudEventPage
import run.ostrom.server.runners.domain.CloudInput
import run.ostrom.server.runners.domain.CloudInputPage
import run.ostrom.server.runners.domain.CloudSubagent
import run.ostrom.server.runners.domain.InputOutcome
import run.ostrom.server.runners.domain.NewInput
import run.ostrom.server.runners.domain.PendingWake
import run.ostrom.server.runners.domain.PermissionBypass
import run.ostrom.server.runners.domain.PermissionRequestRecord
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** A conversation store in memory: events appended by the test, inputs kept, every call recorded. */
class FakeConversationStore(private val sessions: InMemoryCloudSessionsRepository) : CloudConversationStore {
    val events = mutableListOf<CloudEvent>()
    val inputs = mutableListOf<CloudInput>()
    val cleared = mutableListOf<Uuid>()
    var wakes: List<PendingWake> = emptyList()
    var request: PermissionRequestRecord? = null
    var replay: CloudInput? = null
    var updates = true
    var subagents: List<CloudSubagent> = emptyList()

    /** Runs once at each [lastSeq], to append events while a stream follows. */
    var onLastSeq: (() -> Unit)? = null

    override suspend fun events(
        orgId: Uuid,
        sessionId: Uuid,
        after: Long?,
        before: Long?,
        limit: Int,
        feed: String?,
    ): CloudEventPage {
        val page = events.filter { it.seq > (after ?: 0) }.take(limit)
        val more = events.count { it.seq > (after ?: 0) } > limit
        val last = events.lastOrNull()?.seq ?: 0
        return CloudEventPage(page, events.firstOrNull()?.seq, last, page.lastOrNull()?.seq?.takeIf { more }, null)
    }

    override suspend fun subagents(orgId: Uuid, sessionId: Uuid, limit: Int): List<CloudSubagent> = subagents

    override suspend fun lastSeq(orgId: Uuid, sessionId: Uuid): Long {
        onLastSeq?.invoke()
        return events.lastOrNull()?.seq ?: 0
    }

    /** The last input asked for. */
    var lastNew: NewInput? = null

    override suspend fun insertInput(new: NewInput, pendingLimit: Int): InputOutcome {
        lastNew = new
        replay?.let { return InputOutcome.Replayed(it) }
        val session = sessions.sessions.getValue(new.input.sessionId)
        new.permissionCheck?.invoke(AnswerContext(request, session.state, session.desiredState, session.epoch))
        inputs += new.input
        return InputOutcome.Accepted(new.input)
    }

    override suspend fun input(orgId: Uuid, sessionId: Uuid, id: Uuid): CloudInput? =
        inputs.firstOrNull { it.id == id && it.sessionId == sessionId }

    override suspend fun inputs(orgId: Uuid, sessionId: Uuid, beforeId: Uuid?, limit: Int): CloudInputPage =
        CloudInputPage(inputs.filter { it.sessionId == sessionId }.reversed().take(limit), null)

    override suspend fun clearWake(orgId: Uuid, sessionId: Uuid) {
        cleared += sessionId
    }

    override suspend fun pendingWakes(limit: Int): List<PendingWake> = wakes

    override suspend fun cancelEnded(limit: Int, now: Instant): Int = 0

    val purged = mutableListOf<Instant>()

    override suspend fun purge(before: Instant, limit: Int): Int {
        purged += before
        return 0
    }

    override suspend fun setPermissionBypass(
        orgId: Uuid,
        sessionId: Uuid,
        bypass: PermissionBypass?,
        now: Instant,
    ): Boolean = change(sessionId, now) { it.copy(permissionBypass = bypass) }

    override suspend fun setAgentOption(
        orgId: Uuid,
        sessionId: Uuid,
        effort: Boolean,
        value: String,
        now: Instant,
    ): Boolean = change(sessionId, now) { if (effort) it.copy(effort = value) else it.copy(model = value) }

    private fun change(
        id: Uuid,
        now: Instant,
        update: (run.ostrom.server.runners.domain.CloudSession) -> run.ostrom.server.runners.domain.CloudSession,
    ): Boolean {
        if (!updates) return false
        val current = sessions.sessions.getValue(id)
        sessions.sessions[id] = update(current).copy(updatedAt = now)
        return true
    }
}
