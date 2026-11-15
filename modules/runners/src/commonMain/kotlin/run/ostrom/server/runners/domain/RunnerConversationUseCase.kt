// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.domain

import run.ostrom.server.kernel.auth.MemberLogins
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

/**
 * The agent side of the runner channel (TeamCode's `RunnerConversationUseCase`): the agent's configuration, the
 * transcript's upload, the inputs' delivery and their acknowledgements. Every call is fenced: only the current
 * execution of a conversation session that still holds one is served, anything else is `409 execution_fenced`.
 */
interface RunnerConversationUseCase {
    /**
     * @return How the runner starts its agent.
     * @throws ExecutionFencedException When the execution is no longer the session's live one.
     * @throws CloudConversationException `terminal_session` for a session whose agent runs in a terminal.
     */
    suspend fun agent(execution: RunnerExecution): RunnerAgentConfig

    /**
     * Stores a batch in order. A turn that starts or ends counts as the session's activity; with the owner's
     * standing approval on, each tool use the batch asks for is answered allowed.
     *
     * @return What was stored.
     * @throws CloudConversationException `invalid_events` for a batch out of bounds or not consecutive.
     */
    suspend fun events(execution: RunnerExecution, events: List<RunnerEvent>): RunnerEventsAck

    /**
     * Long-poll: waits up to [waitMillis] for inputs and returns them `delivered` to this execution, only while the
     * agent can take them (the session runs, desired running). With [known], the desired state the runner last knew,
     * the poll also ends as soon as the session's differs.
     *
     * @return The inputs and the session's desired state.
     * @throws CloudConversationException `invalid_state` for a [known] other than running or paused.
     */
    suspend fun poll(execution: RunnerExecution, waitMillis: Long, known: String?, accepts: RunnerAccepts): RunnerInputs

    /**
     * Records the runner's outcomes for the inputs it was handed.
     *
     * @throws CloudConversationException `invalid_acks` for too many acknowledgements or an unstable reason.
     */
    suspend fun acknowledge(execution: RunnerExecution, acks: List<RunnerInputAck>)
}

/**
 * [RunnerConversationUseCase] on the sessions and the conversation's stores.
 *
 * @param sessions The sessions.
 * @param runner The runner side of the conversation's tables.
 * @param conversations The inputs, for the owner's standing approval.
 * @param logins The members' logins, to tell the agent whom it runs for.
 * @param clock The time.
 * @param sleep Waits between two reads of a long-poll.
 */
@Suppress("LongParameterList") // TeamCode's collaborators, plus Ostrom's logins.
class RunnerConversationUseCaseImpl(
    private val sessions: CloudSessionsRepository,
    private val runner: RunnerConversationStore,
    private val conversations: CloudConversationStore,
    private val logins: MemberLogins,
    private val clock: Clock,
    private val sleep: suspend (Long) -> Unit = { delay(it.milliseconds) },
) : RunnerConversationUseCase {
    override suspend fun agent(execution: RunnerExecution): RunnerAgentConfig {
        val session = live(execution)
        val claude = session.agent == AgentKind.CLAUDE
        // Read for the current owner at each execution start.
        val username = Uuid.parseOrNull(session.ownerId)?.let { logins.loginsOf(session.orgId, listOf(it))[it] }
        return RunnerAgentConfig(
            kind = session.agent,
            model = session.model,
            effort = session.effort,
            conversation = runner.conversation(session.orgId, session.id),
            permissionRelay = claude,
            permissionDeadlineMinutes = 0,
            liveText = claude,
            backgroundTasks = claude,
            owner = username?.let { RunnerOwner(it, null) },
        )
    }

    override suspend fun events(execution: RunnerExecution, events: List<RunnerEvent>): RunnerEventsAck {
        if (events.size > CloudConversationRules.MAX_EVENTS_PER_BATCH) {
            throw CloudConversationRules.invalid(
                "invalid_events",
                "Send at most ${CloudConversationRules.MAX_EVENTS_PER_BATCH} events per batch.",
            )
        }
        if (events.any { it.sequence < 1 } || events.zipWithNext().any { (a, b) -> b.sequence != a.sequence + 1 }) {
            throw CloudConversationRules.invalid("invalid_events", "Send consecutive positive sequence numbers.")
        }
        val session = live(execution)
        val ack = runner.appendRunnerEvents(session.orgId, session.id, execution.epoch, events)
        val accepted = events.filter { it.sequence <= ack.acceptedThrough }
        session.permissionBypass?.takeIf { it.by == session.ownerId }?.let { bypass ->
            accepted.filter { it.type == CloudEventType.PERMISSION_REQUEST }.forEach { approve(session, bypass, it) }
        }
        // A turn that starts or ends is the agent working: the session is not idle.
        val working = session.state == SessionState.RUNNING && session.desiredState == DesiredState.RUNNING
        if (working && accepted.any { it.turn != null }) sessions.touchActivity(session.id, clock.now())
        return ack
    }

    /**
     * The owner's standing approval: answers the tool use [event] asks for with an allow written by the owner,
     * through the same checks and insert as a member's answer, so the runner receives it like one. A question is
     * never answered, and a request the checks refuse stays open for the owner. The idempotency key makes a batch
     * the runner sends again answer once.
     */
    private suspend fun approve(session: CloudSession, bypass: PermissionBypass, event: RunnerEvent) {
        val data = storedObject(event.data)
        val requestId = (data?.get("requestId") as? JsonPrimitive)?.contentOrNull
            ?.takeIf(CloudConversationRules::isPermissionRequestId)
        val question = (data?.get("question") as? JsonPrimitive)?.booleanOrNull == true
        if (requestId == null || question) return
        val answer = PermissionAnswer(requestId, true, null, emptyList())
        val text = CloudConversationRules.bypassAllowText(requestId)
        val input = CloudInput(
            id = Uuid.random(),
            orgId = session.orgId,
            sessionId = session.id,
            kind = CloudInputKind.PERMISSION,
            status = CloudInputStatus.QUEUED,
            authorId = bypass.by,
            credentialId = bypass.credentialId,
            text = text,
            wake = false,
            clientKey = "bypass:$requestId",
            requestDigest = HASHER.hashBlocking("permission\n$text".encodeToByteArray()).toHexString(),
            createdAt = clock.now(),
        )
        val check = { context: AnswerContext ->
            if (context.state != SessionState.RUNNING || context.desiredState != DesiredState.RUNNING) {
                throw CloudConversationRules.conflict("nothing_to_answer", "The session has no running agent.")
            }
            val asked = context.request ?: throw CloudConversationRules.conflict(
                "unknown_permission_request",
                "No permission request with this id waits in the session's recent transcript.",
            )
            CloudConversationRules.checkAnswer(asked, answer, true, context.currentEpoch, standing = true)
        }
        try {
            conversations.insertInput(
                NewInput(input, null, permissionRequestId = requestId, permissionCheck = check),
                CloudConversationRules.MAX_PENDING_INPUTS,
            )
        } catch (_: CloudConversationException) {
            // A refusal leaves the request to the owner; it never fails the runner's upload.
        }
    }

    @Suppress("ReturnCount") // TeamCode's three ends of a long-poll: a state change, inputs, the deadline.
    override suspend fun poll(
        execution: RunnerExecution,
        waitMillis: Long,
        known: String?,
        accepts: RunnerAccepts,
    ): RunnerInputs {
        // Only the two states a runner acts on: a deleted session fences the call anyway.
        val knownState = known?.let { wire ->
            DesiredState.entries.firstOrNull { it.wire == wire && it != DesiredState.TERMINATED }
                ?: throw CloudConversationRules.invalid("invalid_state", "Send the desired state as running or paused.")
        }
        val deadline = clock.now() + waitMillis.coerceIn(0, CloudConversationRules.MAX_RUNNER_WAIT_MILLIS).milliseconds
        while (true) {
            val session = live(execution)
            if (knownState != null && session.desiredState != knownState) {
                return RunnerInputs(emptyList(), session.desiredState)
            }
            if (session.desiredState == DesiredState.RUNNING && session.state == SessionState.RUNNING) {
                val delivered = deliver(session, execution.epoch, accepts)
                if (delivered.isNotEmpty()) return RunnerInputs(delivered, session.desiredState)
            }
            if (clock.now() >= deadline) return RunnerInputs(emptyList(), session.desiredState)
            sleep(POLL_MILLIS)
        }
    }

    /** The next inputs, less those this runner cannot take and those kept from an agent on the owner's GitHub. */
    private suspend fun deliver(session: CloudSession, epoch: Long, accepts: RunnerAccepts): List<CloudInput> {
        val inputs = runner.deliver(session.orgId, session.id, epoch, DELIVERY_BATCH)
        val refused = inputs.mapNotNull { input ->
            // Checked again at delivery (audit M2): a non-owner's input never reaches an agent on the owner's access.
            val kept = CloudConversationRules.ownerOnly(session, input.authorId) &&
                !CloudConversationRules.onlyStops(input)
            val reason = unsupported(input, accepts) ?: "github_owner_only".takeIf { kept }
            reason?.let { RunnerInputAck(input.id, false, it) }
        }
        if (refused.isNotEmpty()) runner.acknowledge(session.orgId, session.id, epoch, refused)
        val ids = refused.map { it.id }.toSet()
        return inputs.filter { it.id !in ids }
    }

    override suspend fun acknowledge(execution: RunnerExecution, acks: List<RunnerInputAck>) {
        if (acks.size > MAX_ACKS || acks.any { it.reason != null && !REASON.matches(it.reason) }) {
            throw CloudConversationRules.invalid(
                "invalid_acks",
                "Acknowledge at most $MAX_ACKS inputs with stable reason codes.",
            )
        }
        val session = live(execution)
        runner.acknowledge(session.orgId, session.id, execution.epoch, acks)
    }

    /** The session of a runner call when its execution is the current one, still running or starting. */
    private suspend fun live(execution: RunnerExecution): CloudSession {
        val session = sessions.get(execution.orgId, execution.sessionId)
            ?.takeIf { it.epoch == execution.epoch && it.state.holdsExecution }
            ?.takeIf { it.desiredState != DesiredState.TERMINATED }
            ?: throw ExecutionFencedException()
        if (session.mode != SessionMode.CONVERSATION) {
            throw CloudConversationRules.conflict("terminal_session", "This session's agent runs in a terminal.")
        }
        return session
    }

    /** Constants of the channel. */
    companion object {
        /** Between two reads of a long-poll. */
        const val POLL_MILLIS: Long = 500L

        /** Inputs handed in one poll. */
        const val DELIVERY_BATCH: Int = 25

        /** Acknowledgements in one call. */
        const val MAX_ACKS: Int = DELIVERY_BATCH * 4
    }
}

/** Why an input of a kind [accepts] does not announce is refused instead of delivered; null when it is taken. */
private fun unsupported(input: CloudInput, accepts: RunnerAccepts): String? = when (input.kind) {
    CloudInputKind.STOP_TASK -> "task_stop_unsupported".takeUnless { accepts.taskStops }
    CloudInputKind.SET_MODEL -> "model_change_unsupported".takeUnless { accepts.modelChanges }
    CloudInputKind.SET_EFFORT -> "effort_change_unsupported".takeUnless { accepts.effortChanges }
    else -> null
}

/** The reasons a runner refuses an input with: stable codes. */
private val REASON = Regex("[a-z_]{1,64}")

private val HASHER = CryptographyProvider.Default.get(SHA256).hasher()
