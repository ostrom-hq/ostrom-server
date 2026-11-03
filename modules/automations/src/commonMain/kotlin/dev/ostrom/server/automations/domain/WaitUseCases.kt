// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.automations.domain

import dev.ostrom.server.kernel.auth.MemberDirectory
import kotlinx.serialization.json.JsonElement
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A question to ask.
 *
 * @property question The question.
 * @property sessionId The session that waits, if any.
 * @property runId The workflow run that waits, if any.
 * @property assignee The member asked; the caller when null.
 * @property kind `human_request`, the only kind a caller asks.
 */
data class AskWait(
    val question: WaitSpec,
    val sessionId: String? = null,
    val runId: String? = null,
    val assignee: String? = null,
    val kind: String? = null,
)

/**
 * The wait asked.
 *
 * @property wait The wait.
 * @property replayed Whether it was asked before with the same idempotency key, and is returned unchanged.
 */
data class AskedWait(val wait: Wait, val replayed: Boolean)

/** Asking a person. */
interface AskWaitUseCase {
    /**
     * Asks [request]; with an [idempotencyKey] already used by the caller, returns the first wait.
     *
     * @throws InvalidWaitException When the question, its session or the key is malformed.
     * @throws InvalidAssigneeException When the assignee is not an active member.
     * @throws AgentAssigneeRefusedException When an agent token asks someone other than its person.
     * @throws WaitLimitException When too many waits are open for the session or the caller.
     */
    suspend fun ask(caller: WaitCaller, request: AskWait, idempotencyKey: String?): AskedWait
}

/**
 * [AskWaitUseCase].
 *
 * @param repository Where waits are stored.
 * @param members Who the members are.
 * @param events Told of the new wait.
 * @param clock The time.
 */
class AskWaitUseCaseImpl(
    private val repository: WaitsRepository,
    private val members: MemberDirectory,
    private val events: WaitEvents,
    private val clock: Clock,
) : AskWaitUseCase {
    override suspend fun ask(caller: WaitCaller, request: AskWait, idempotencyKey: String?): AskedWait {
        requireWait(idempotencyKey == null || KEY.matches(idempotencyKey)) {
            "Idempotency-Key is 1 to 200 visible characters."
        }
        earlier(caller, idempotencyKey)?.let { return AskedWait(it, replayed = true) }
        val wait = newWait(caller, request, idempotencyKey)
        val limit = if (wait.sessionId == null) MAX_OPEN_ALONE else MAX_OPEN
        return when (repository.createWithinLimit(wait, limit)) {
            CreateOutcome.CREATED -> AskedWait(wait, replayed = false).also { events.created(wait) }
            CreateOutcome.AT_LIMIT -> throw WaitLimitException(limit)
            CreateOutcome.DUPLICATE -> AskedWait(
                earlier(caller, idempotencyKey) ?: throw WaitKeyConflictException(),
                replayed = true,
            )
        }
    }

    private suspend fun earlier(caller: WaitCaller, key: String?): Wait? =
        key?.let { repository.findByIdempotencyKey(caller.orgId, caller.memberId, it) }

    private suspend fun newWait(caller: WaitCaller, request: AskWait, key: String?): Wait {
        requireWait(request.kind == null || request.kind == HUMAN_REQUEST) { "kind is $HUMAN_REQUEST." }
        val reference = WaitRules.REFERENCE
        requireWait(request.sessionId == null || reference.matches(request.sessionId)) { "sessionId is malformed." }
        requireWait(request.runId == null || reference.matches(request.runId)) { "runId is malformed." }
        val assignee = request.assignee ?: caller.memberId
        if (caller.agent && assignee != caller.memberId) throw AgentAssigneeRefusedException()
        if (assignee != caller.memberId && !members.isActiveMember(caller.orgId, assignee)) {
            throw InvalidAssigneeException()
        }
        // To the millisecond, as stored: the wait returned now equals the one read back later.
        val now = Instant.fromEpochMilliseconds(clock.now().toEpochMilliseconds())
        return Wait(
            id = Uuid.random(),
            orgId = caller.orgId,
            kind = HUMAN_REQUEST,
            status = WaitStatus.OPEN,
            sessionId = request.sessionId,
            runId = request.runId,
            assignee = assignee,
            createdBy = caller.memberId,
            question = WaitRules.question(request.question),
            answer = null,
            createdAt = now,
            resolvedAt = null,
            idempotencyKey = key,
        )
    }

    private companion object {
        const val HUMAN_REQUEST = "human_request"
        const val MAX_OPEN = 20
        const val MAX_OPEN_ALONE = 100
        private val KEY = Regex("[\\x21-\\x7e]{1,200}")
    }
}

/**
 * What a listing asks for.
 *
 * @property mine Only the waits asked of the caller.
 * @property assignee `all`, or a member, for an administrator; the caller's own otherwise.
 * @property status `open` (the default), `resolved`, `cancelled` or `all`.
 * @property sessionId Only this session's.
 * @property runId Only this run's.
 * @property before The last wait of the previous page.
 * @property limit The page size, 1 to 100 (50).
 * @property agentSession Only this agent session's, in any of its segments.
 */
data class WaitQuery(
    val mine: Boolean = false,
    val assignee: String? = null,
    val status: String? = null,
    val sessionId: String? = null,
    val runId: String? = null,
    val before: Uuid? = null,
    val limit: Int? = null,
    val agentSession: String? = null,
)

/**
 * A page of waits.
 *
 * @property items The waits, newest first.
 * @property next The cursor of the next page, if any.
 */
data class WaitPage(val items: List<Wait>, val next: Uuid?)

/** Reading waits. */
interface ReadWaitsUseCase {
    /**
     * A wait the caller may read: its assignee, its asker or an administrator; for an agent token, only a wait its
     * person asked.
     *
     * @throws WaitNotFoundException Otherwise.
     */
    suspend fun get(caller: WaitCaller, id: Uuid): Wait

    /**
     * The waits a query names; for an agent token, the waits its person asked, whatever `mine` and `assignee` say.
     *
     * @throws InvalidWaitException When the query is malformed.
     * @throws WaitForbiddenException When a member who is no administrator lists someone else's.
     */
    suspend fun list(caller: WaitCaller, query: WaitQuery): WaitPage
}

/**
 * [ReadWaitsUseCase].
 *
 * @param repository Where waits are stored.
 */
class ReadWaitsUseCaseImpl(private val repository: WaitsRepository) : ReadWaitsUseCase {
    override suspend fun get(caller: WaitCaller, id: Uuid): Wait = visibleWait(repository, caller, id)

    override suspend fun list(caller: WaitCaller, query: WaitQuery): WaitPage {
        val limit = query.limit ?: DEFAULT_LIMIT
        requireWait(limit in 1..MAX_LIMIT) { "limit is 1 to $MAX_LIMIT." }
        val wire = query.status ?: WaitStatus.OPEN.wire
        val status = if (wire == ALL) {
            null
        } else {
            WaitStatus.entries.firstOrNull { it.wire == wire }
                ?: throw InvalidWaitException("status is open, resolved, cancelled or all.")
        }
        val filter = scope(caller, query).copy(
            status = status,
            sessionId = query.sessionId,
            agentSession = query.agentSession,
            runId = query.runId,
        )
        val items = repository.list(filter, query.before, limit + 1)
        return WaitPage(items.take(limit), if (items.size > limit) items[limit - 1].id else null)
    }

    private fun scope(caller: WaitCaller, query: WaitQuery): WaitFilter {
        val others = query.assignee != null && query.assignee != caller.memberId && !query.mine
        if (others && !caller.admin) throw WaitForbiddenException("Only an administrator lists someone else's waits.")
        return when {
            caller.agent -> WaitFilter(caller.orgId, createdBy = caller.memberId)
            query.mine -> WaitFilter(caller.orgId, assignee = caller.memberId)
            query.assignee == null -> WaitFilter(caller.orgId, involving = caller.memberId)
            query.assignee == ALL -> WaitFilter(caller.orgId)
            else -> WaitFilter(caller.orgId, assignee = query.assignee)
        }
    }

    private companion object {
        const val ALL = "all"
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 100
    }
}

/** Answering and cancelling waits. */
interface AnswerWaitUseCase {
    /**
     * Answers a wait. The first answer wins; the same member sending the same answer again gets the wait back.
     *
     * @throws WaitNotFoundException When the caller may not read it.
     * @throws WaitForbiddenException When the caller is an agent token (an agent never answers), or is not its
     *   assignee nor an administrator who signed in.
     * @throws WaitClosedException When another answer won, or it was cancelled.
     * @throws InvalidWaitAnswerException When the answer does not fit the question.
     */
    suspend fun answer(caller: WaitCaller, id: Uuid, action: String, values: Map<String, JsonElement>): Wait

    /**
     * Cancels an open wait.
     *
     * @throws WaitNotFoundException When the caller may not read it.
     * @throws WaitForbiddenException When the caller is not its asker, its assignee nor an administrator.
     * @throws WaitClosedException When it is not open any more.
     */
    suspend fun cancel(caller: WaitCaller, id: Uuid): Wait
}

/**
 * [AnswerWaitUseCase].
 *
 * @param repository Where waits are stored.
 * @param events Told of the answer or the cancellation.
 * @param clock The time.
 */
class AnswerWaitUseCaseImpl(
    private val repository: WaitsRepository,
    private val events: WaitEvents,
    private val clock: Clock,
) : AnswerWaitUseCase {
    override suspend fun answer(
        caller: WaitCaller,
        id: Uuid,
        action: String,
        values: Map<String, JsonElement>,
    ): Wait {
        if (caller.agent) throw WaitForbiddenException("An agent never answers a wait: its person does.")
        val wait = visibleWait(repository, caller, id)
        if (wait.assignee != caller.memberId && !caller.actsAsAdmin) {
            throw WaitForbiddenException("Only the member asked answers this wait.")
        }
        val open = wait.status == WaitStatus.OPEN
        val resolved = if (open) record(caller, wait, action, values) else null
        return resolved ?: replayed(if (open) visibleWait(repository, caller, id) else wait, caller, action, values)
    }

    /** Checks and stores an answer; null when another answer won meanwhile. */
    private suspend fun record(
        caller: WaitCaller,
        wait: Wait,
        action: String,
        values: Map<String, JsonElement>,
    ): Wait? {
        WaitRules.answer(wait.question, action, values)
        val answer = WaitAnswer(action, values, caller.memberId, clock.now())
        return repository.resolve(caller.orgId, wait.id, answer)?.also { events.resolved(it) }
    }

    override suspend fun cancel(caller: WaitCaller, id: Uuid): Wait {
        val wait = visibleWait(repository, caller, id)
        val allowed = wait.createdBy == caller.memberId || wait.assignee == caller.memberId || caller.actsAsAdmin
        if (!allowed) throw WaitForbiddenException("Only the asker, the member asked or an administrator cancels it.")
        val cancelled = repository.cancel(caller.orgId, id, clock.now()) ?: throw WaitClosedException()
        events.cancelled(cancelled)
        return cancelled
    }

    /** The same member sending the same answer again gets the wait back; anyone else learns it is closed. */
    private fun replayed(wait: Wait, caller: WaitCaller, action: String, values: Map<String, JsonElement>): Wait {
        val answer = wait.answer
        val same = answer != null && answer.author == caller.memberId && answer.action == action &&
            answer.values == values
        return if (same) wait else throw WaitClosedException()
    }
}

/**
 * A wait the caller may read: its assignee, its asker or an administrator; for an agent token, only a wait its person
 * asked (never one someone else asked of its person). Otherwise not found.
 */
private suspend fun visibleWait(repository: WaitsRepository, caller: WaitCaller, id: Uuid): Wait {
    val wait = repository.get(caller.orgId, id)?.takeIf { it.createdBy == caller.memberId || mayReadAsked(caller, it) }
    return wait ?: throw WaitNotFoundException()
}

/** Whether [caller] reads [wait] as its assignee or an administrator: never an agent token. */
private fun mayReadAsked(caller: WaitCaller, wait: Wait): Boolean =
    !caller.agent && (caller.admin || wait.assignee == caller.memberId)
