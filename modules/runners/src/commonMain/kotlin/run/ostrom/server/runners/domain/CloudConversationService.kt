// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.domain

import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.errors.ErrorKind
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Transcript reads, messages, interrupts, permission answers, task stops and the agent's options for members
 * (TeamCode's `CloudConversationService`), on conversation sessions (`mode: conversation`). Ostrom has no access
 * policy: the owner and the organization's administrators reach a session, anyone else gets `404`.
 */
@Suppress("TooManyFunctions") // TeamCode's one service of the conversation routes.
interface CloudConversationService {
    /**
     * A page of the transcript. A terminal session has none: an empty page.
     *
     * @throws CloudConversationException For a malformed page or feed.
     * @throws CloudSessionNotFoundException When the caller cannot reach the session.
     */
    @Suppress("LongParameterList") // The page's coordinates, as TeamCode's.
    suspend fun events(caller: Caller, id: Uuid, after: Long?, before: Long?, limit: Int, feed: String?): CloudEventPage

    /** @return The session's subagents, oldest first. */
    suspend fun subagents(caller: Caller, id: Uuid): List<CloudSubagent>

    /**
     * Checks the stream may open, before its answer is committed.
     *
     * @throws CloudConversationException `terminal_session`, or `invalid_cursor`.
     * @throws CloudSessionNotFoundException When the caller cannot reach the session.
     */
    suspend fun openStream(caller: Caller, id: Uuid, after: Long)

    /**
     * Follows the transcript from [after]: [emit] receives each new batch, [idle] runs when nothing arrived for a
     * while (the route sends a keep-alive). The caller is checked again every 15 s, so a lost access ends the stream
     * with the refusal. Returns once the session has ended and every event was sent.
     */
    suspend fun follow(
        caller: Caller,
        id: Uuid,
        after: Long,
        emit: suspend (List<CloudEvent>) -> Unit,
        idle: suspend () -> Unit,
    )

    /**
     * Sends a message, waking a paused session unless [InputRequest.wake] is false.
     *
     * @return The input, `woke` when it resumed the session.
     */
    suspend fun send(caller: Caller, principal: AuthenticatedPrincipal, id: Uuid, request: InputRequest): InputOutcome

    /** Stops the current turn. Interrupts are delivered before queued messages and never wake a session. */
    suspend fun interrupt(
        caller: Caller,
        principal: AuthenticatedPrincipal,
        id: Uuid,
        request: InputRequest,
    ): InputOutcome

    /** Stops the background task [taskId] of the running agent; never wakes a session. */
    suspend fun stopTask(
        caller: Caller,
        principal: AuthenticatedPrincipal,
        id: Uuid,
        taskId: String,
        request: InputRequest,
    ): InputOutcome

    /** Answers a `permission.request` of the running agent; never wakes a session. */
    suspend fun answer(
        caller: Caller,
        principal: AuthenticatedPrincipal,
        id: Uuid,
        answer: PermissionAnswer,
        request: InputRequest,
    ): InputOutcome

    /** @return The session's inputs, newest first. */
    suspend fun inputs(caller: Caller, id: Uuid, before: Uuid?, limit: Int): CloudInputPage

    /** @return One input of the session. */
    suspend fun input(caller: Caller, id: Uuid, inputId: Uuid): CloudInput

    /** Turns the owner's standing approval on or off; the owner only, in person. @return The session. */
    suspend fun setPermissionBypass(
        caller: Caller,
        principal: AuthenticatedPrincipal,
        id: Uuid,
        enabled: Boolean,
    ): CloudSession

    /** Changes the agent's model, or its effort when [effort] is true; the owner only. @return The session. */
    suspend fun setAgentOption(
        caller: Caller,
        principal: AuthenticatedPrincipal,
        id: Uuid,
        effort: Boolean,
        value: String,
    ): CloudSession

    /**
     * Wakes the sessions holding a message that asked to wake them and did not; run by the maintenance worker.
     *
     * @return How many it woke.
     */
    suspend fun wakePending(limit: Int): Int
}

/**
 * [CloudConversationService] on the store and the sessions.
 *
 * @param store The transcript and inputs.
 * @param sessions The sessions.
 * @param wake Resumes a paused session for a message, as for the answer to a wait.
 * @param audit The organization's audit log.
 * @param clock The time.
 * @param sleep Waits between two polls of a stream.
 */
@Suppress("TooManyFunctions", "LongParameterList") // TeamCode's one service; its collaborators.
class CloudConversationServiceImpl(
    private val store: CloudConversationStore,
    private val sessions: CloudSessionsRepository,
    private val wake: WakeCloudSessionUseCase,
    private val audit: OrgAuditTrail,
    private val clock: Clock,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : CloudConversationService {
    override suspend fun events(
        caller: Caller,
        id: Uuid,
        after: Long?,
        before: Long?,
        limit: Int,
        feed: String?,
    ): CloudEventPage {
        CloudConversationRules.validatePage(limit, after, before)
        CloudConversationRules.validateFeed(feed)
        val session = reach(caller, id)
        return store.events(caller.orgId, session.id, after, before, limit, feed)
    }

    override suspend fun subagents(caller: Caller, id: Uuid): List<CloudSubagent> =
        store.subagents(caller.orgId, reach(caller, id).id, CloudConversationRules.MAX_SUBAGENTS)

    override suspend fun openStream(caller: Caller, id: Uuid, after: Long) {
        if (after < 0) throw CloudConversationRules.invalid("invalid_cursor", "Use a non-negative event sequence.")
        conversation(reach(caller, id))
    }

    override suspend fun follow(
        caller: Caller,
        id: Uuid,
        after: Long,
        emit: suspend (List<CloudEvent>) -> Unit,
        idle: suspend () -> Unit,
    ) {
        var cursor = after
        var session = reach(caller, id)
        var checkedAt = clock.now()
        var quiet = 0L
        while (true) {
            // The caller is checked again every 15 s, a busy transcript included.
            if ((clock.now() - checkedAt).inWholeMilliseconds >= RECHECK_MILLIS) {
                session = reach(caller, id)
                checkedAt = clock.now()
            }
            // One indexed read per poll; the page is read only when something new arrived.
            val page = if (store.lastSeq(caller.orgId, id) > cursor) {
                store.events(caller.orgId, id, cursor, null, CloudConversationRules.MAX_PAGE, null)
            } else {
                null
            }
            if (page != null && page.events.isNotEmpty()) {
                emit(page.events)
                cursor = page.events.last().seq
                quiet = 0
                if (page.nextAfter != null) continue
            } else if (session.state.isFinal) {
                return
            } else if (quiet >= KEEPALIVE_MILLIS) {
                idle()
                quiet = 0
            }
            sleep(POLL_MILLIS)
            quiet += POLL_MILLIS
        }
    }

    override suspend fun send(
        caller: Caller,
        principal: AuthenticatedPrincipal,
        id: Uuid,
        request: InputRequest,
    ): InputOutcome {
        val text = request.text ?: throw CloudConversationRules.invalid("invalid_message", "Send a message text.")
        CloudConversationRules.validateMessage(text)
        val session = writable(caller, id)
        requireInstructor(caller, session)
        val input = newInput(caller, principal, session, CloudInputKind.MESSAGE, text, request)
        return when (val outcome = store.insertInput(NewInput(input, request.expectedLastEventSeq), MAX_PENDING)) {
            is InputOutcome.Replayed -> outcome
            // Only a sleeping session is woken: a running one needs no lookup nor write.
            is InputOutcome.Accepted -> if (request.wake && sleeping(session)) {
                InputOutcome.Accepted(outcome.input.copy(woke = wakeFor(outcome.input)))
            } else {
                outcome
            }
        }
    }

    override suspend fun interrupt(
        caller: Caller,
        principal: AuthenticatedPrincipal,
        id: Uuid,
        request: InputRequest,
    ): InputOutcome {
        val session = writable(caller, id)
        if (session.state != SessionState.RUNNING) {
            throw CloudConversationRules.conflict(
                "nothing_to_interrupt",
                "The session has no running agent to interrupt.",
            )
        }
        val input = newInput(caller, principal, session, CloudInputKind.INTERRUPT, null, request.copy(wake = false))
        return store.insertInput(NewInput(input, null), MAX_PENDING)
    }

    override suspend fun stopTask(
        caller: Caller,
        principal: AuthenticatedPrincipal,
        id: Uuid,
        taskId: String,
        request: InputRequest,
    ): InputOutcome {
        CloudConversationRules.validateTaskId(taskId)
        val session = writable(caller, id)
        if (session.state != SessionState.RUNNING || session.desiredState != DesiredState.RUNNING) {
            throw CloudConversationRules.conflict(
                "nothing_to_interrupt",
                "The session has no running agent whose tasks could be stopped.",
            )
        }
        val input = newInput(caller, principal, session, CloudInputKind.STOP_TASK, taskId, request.copy(wake = false))
        return store.insertInput(NewInput(input, null), MAX_PENDING)
    }

    override suspend fun answer(
        caller: Caller,
        principal: AuthenticatedPrincipal,
        id: Uuid,
        answer: PermissionAnswer,
        request: InputRequest,
    ): InputOutcome {
        CloudConversationRules.validatePermissionAnswer(answer)
        val session = writable(caller, id)
        // A bare denial only stops a tool use, like an interrupt: it stays open to everyone who may message.
        if (!answer.isBareDenial) requireInstructor(caller, session)
        // Whoever reaches a session (its owner, an administrator) may approve its tool uses: Ostrom shares no session
        // with other members yet, so TeamCode's `permission_owner_only` is never this route's answer.
        val approver = true
        val asked = request.copy(wake = false)
        val input = newInput(caller, principal, session, CloudInputKind.PERMISSION, request.text, asked)
        // Checked by the store under the session lock, after an idempotent retry was replayed and atomically with
        // the insert (two members answering at once: the second sees the first).
        val check = { context: AnswerContext ->
            if (context.state != SessionState.RUNNING || context.desiredState != DesiredState.RUNNING) {
                throw CloudConversationRules.conflict(
                    "nothing_to_answer",
                    "The session has no running agent waiting for an answer.",
                )
            }
            val asked = context.request ?: throw CloudConversationRules.conflict(
                "unknown_permission_request",
                "No permission request with this id waits in the session's recent transcript.",
            )
            CloudConversationRules.checkAnswer(asked, answer, approver, context.currentEpoch)
        }
        return store.insertInput(NewInput(input, null, answer.requestId, check), MAX_PENDING)
    }

    override suspend fun inputs(caller: Caller, id: Uuid, before: Uuid?, limit: Int): CloudInputPage {
        if (limit !in 1..CloudConversationRules.MAX_INPUT_PAGE) {
            throw CloudConversationRules.invalid(
                "invalid_limit",
                "Use a page size between 1 and ${CloudConversationRules.MAX_INPUT_PAGE}.",
            )
        }
        return store.inputs(caller.orgId, reach(caller, id).id, before, limit)
    }

    override suspend fun input(caller: Caller, id: Uuid, inputId: Uuid): CloudInput =
        store.input(caller.orgId, reach(caller, id).id, inputId)
            ?: throw CloudConversationException(ErrorKind.NOT_FOUND, "not_found", "Input not found.")

    override suspend fun setPermissionBypass(
        caller: Caller,
        principal: AuthenticatedPrincipal,
        id: Uuid,
        enabled: Boolean,
    ): CloudSession {
        val session = conversation(sessions.ownedBy(caller, id))
        // An agent could approve its own tool uses, a service key acts for nobody in person.
        if (principal.kind != PrincipalKind.USER) {
            throw CloudConversationException(
                ErrorKind.FORBIDDEN,
                "bypass_owner_only",
                "Only the session's owner, in person, turns Bypass on or off.",
            )
        }
        ended(session)
        if ((session.permissionBypass != null) == enabled) return session
        val now = clock.now()
        val bypass = if (enabled) PermissionBypass(caller.memberId, principal.credentialId, now) else null
        if (!store.setPermissionBypass(caller.orgId, id, bypass, now)) throw notRunning()
        val event = "cloud.session.permission_bypass_" + if (enabled) "enabled" else "disabled"
        audit.record(OrgAuditRecord(caller.orgId, principal, event, SUBJECT, id.toString()))
        return sessions.ownedBy(caller, id)
    }

    override suspend fun setAgentOption(
        caller: Caller,
        principal: AuthenticatedPrincipal,
        id: Uuid,
        effort: Boolean,
        value: String,
    ): CloudSession {
        if (effort) CloudConversationRules.validateEffort(value) else CloudConversationRules.validateModel(value)
        val session = sessions.ownedBy(caller, id)
        ended(session)
        if ((if (effort) session.effort else session.model) == value) return session
        val now = clock.now()
        if (!store.setAgentOption(caller.orgId, id, effort, value, now)) throw notRunning()
        val event = if (effort) "cloud.session.effort_changed" else "cloud.session.model_changed"
        audit.record(OrgAuditRecord(caller.orgId, principal, event, SUBJECT, id.toString()))
        val changed = sessions.ownedBy(caller, id)
        // A running conversation agent takes it at its next turn; a paused or terminal one at its next start.
        val running = changed.state == SessionState.RUNNING && changed.desiredState == DesiredState.RUNNING
        if (changed.mode == SessionMode.CONVERSATION && running) {
            val kind = if (effort) CloudInputKind.SET_EFFORT else CloudInputKind.SET_MODEL
            // Keyed by the row's write, so choosing a value twice in a row queues one input.
            val key = "${kind.wire}:${changed.updatedAt}:$value"
            val input = newInput(caller, principal, changed, kind, value, InputRequest(value, false, key, key, null))
            store.insertInput(NewInput(input, null), MAX_PENDING)
        }
        return changed
    }

    override suspend fun wakePending(limit: Int): Int {
        var woken = 0
        for (pending in store.pendingWakes(limit)) {
            if (wake.forMessage(pending.orgId, pending.sessionId, pending.inputId) == CloudSessionWake.RESUMED) woken++
            // One more try only: a refused wake leaves the message queued for the next resume, as TeamCode's.
            store.clearWake(pending.orgId, pending.sessionId)
        }
        return woken
    }

    /** Resumes the session of a message that asked to; the mark stays for the sweep when the plan refused. */
    private suspend fun wakeFor(input: CloudInput): Boolean {
        val outcome = wake.forMessage(input.orgId, input.sessionId, input.id)
        if (outcome !in RETRIED) store.clearWake(input.orgId, input.sessionId)
        return outcome == CloudSessionWake.RESUMED
    }

    /**
     * Audit M2: an agent that uses its owner's GitHub access does what its messages say. While the session's access
     * is the owner's, only the owner sends it messages and answers its permission requests.
     */
    private fun requireInstructor(caller: Caller, session: CloudSession) {
        if (!CloudConversationRules.ownerOnly(session, caller.memberId)) return
        throw CloudConversationException(
            ErrorKind.FORBIDDEN,
            "github_owner_only",
            "This session's agent uses its owner's GitHub access, so only the owner instructs it. The owner can " +
                "share it with PUT /api/v1/cloud/sessions/${session.id}/github.",
        )
    }

    @Suppress("LongParameterList") // What an input records.
    private fun newInput(
        caller: Caller,
        principal: AuthenticatedPrincipal,
        session: CloudSession,
        kind: CloudInputKind,
        text: String?,
        request: InputRequest,
    ) = CloudInput(
        id = Uuid.random(),
        orgId = caller.orgId,
        sessionId = session.id,
        kind = kind,
        status = CloudInputStatus.QUEUED,
        authorId = caller.memberId,
        credentialId = principal.credentialId,
        text = text,
        wake = request.wake,
        clientKey = request.clientKey,
        requestDigest = request.requestDigest,
        createdAt = clock.now(),
    )

    /** The session, for its owner or an administrator of its organization; `404` for anyone else. */
    private suspend fun reach(caller: Caller, id: Uuid): CloudSession =
        sessions.get(caller.orgId, id)?.takeIf { it.ownerId == caller.memberId || caller.admin }
            ?: throw CloudSessionNotFoundException()

    /** A conversation session that takes inputs. */
    private suspend fun writable(caller: Caller, id: Uuid): CloudSession {
        val session = conversation(reach(caller, id))
        ended(session)
        return session
    }

    private fun conversation(session: CloudSession): CloudSession {
        if (session.mode != SessionMode.CONVERSATION) {
            throw CloudConversationRules.conflict(
                "terminal_session",
                "This session's agent runs in a terminal: attach to it instead.",
            )
        }
        return session
    }

    private fun sleeping(session: CloudSession): Boolean = session.desiredState == DesiredState.PAUSED ||
        session.state == SessionState.PAUSING || session.state == SessionState.PAUSED

    private fun ended(session: CloudSession) {
        if (session.state.isFinal || session.desiredState == DesiredState.TERMINATED) throw notRunning()
    }

    private companion object {
        const val POLL_MILLIS = 1_000L
        const val KEEPALIVE_MILLIS = 15_000L
        const val RECHECK_MILLIS = 15_000L
        const val MAX_PENDING = CloudConversationRules.MAX_PENDING_INPUTS
        const val SUBJECT = "cloud_session"

        /** Refusals of the plan or the owner, which the sweep tries once more. */
        val RETRIED = setOf(
            CloudSessionWake.AT_CAP,
            CloudSessionWake.NO_HOURS,
            CloudSessionWake.CLOSED,
            CloudSessionWake.OWNER_GONE,
        )

        fun notRunning() = CloudConversationRules.conflict(
            "session_not_running",
            "The cloud session is terminated or being deleted.",
        )
    }
}
