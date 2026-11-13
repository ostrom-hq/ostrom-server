// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.todos.domain

import kotlin.time.Instant

/**
 * The phase of one question of the Stop hook.
 *
 * @property wire The contract's value.
 */
enum class TodoStopPhase(val wire: String) {
    /** Anything to hold back? */
    BEGIN("begin"),

    /** The cheap checks ran, and the PRs the session opened are known. */
    VERIFY("verify"),

    /** Every check of the done items ran, for the final verification. */
    SETTLE("settle"),
    ;

    /** Parsing the contract's values. */
    companion object {
        /**
         * Reads a wire value.
         *
         * @param wire The value.
         * @return The phase, or null for another value.
         */
        fun fromWire(wire: String): TodoStopPhase? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * What the Stop hook does next.
 *
 * @property wire The contract's value.
 */
enum class TodoStopAction(val wire: String) {
    /** The stop passes. */
    ALLOW("allow"),

    /** Run the cheap checks and ask again with `verify`. */
    VERIFY("verify"),

    /** Run every check of the done items and ask again with `settle`. */
    SETTLE("settle"),

    /** Refuse the stop, with the resume message. */
    BLOCK("block"),
}

/**
 * The outcome of the checks the hook ran for one item.
 *
 * @property itemId The item.
 * @property checks By position in the item's stored checks.
 */
data class TodoStopItemReport(val itemId: String, val checks: List<TodoCheckReport>)

/**
 * One question of the Stop hook.
 *
 * @property phase Its phase.
 * @property background A background agent or a scheduled wake-up of the session still runs.
 * @property items The checks it ran.
 * @property orphans The pull requests the session opened (`verify`).
 * @property lastMessage The agent's last message, for the owner if the session is stuck.
 */
data class TodoStopInput(
    val phase: TodoStopPhase,
    val background: Boolean = false,
    val items: List<TodoStopItemReport> = emptyList(),
    val orphans: List<String> = emptyList(),
    val lastMessage: String? = null,
)

/**
 * The answer to the Stop hook.
 *
 * @property action What it does.
 * @property list The list as it stands, if any.
 * @property reason The resume message of a block.
 * @property why Why a stop passes: `no_list`, `hook_disabled`, `finished`, `background_task`, `wait_open`,
 *   `escalated`, `settled`.
 * @property escalated Whether the owner was just asked to unblock the session.
 */
data class TodoStopDecision(
    val action: TodoStopAction,
    val list: TodoList?,
    val reason: String? = null,
    val why: String? = null,
    val escalated: Boolean = false,
)

/**
 * One stop of the agent, asked by the Stop hook in up to three phases (TeamCode `docs/todos.md`, "The Stop hook").
 */
fun interface StopTodoUseCase {
    /**
     * Answers one question of the Stop hook. A session without a list, a finished list, a background agent still
     * running or any open wait of the session lets the stop pass without a question; so does a session whose owner
     * an administrator turned the hook off for, before anything is run, counted or written.
     *
     * @param sessionId The session named (an agent token's is its own).
     * @return What the hook does next.
     * @throws TodoException `invalid_stop`, `invalid_proof`.
     */
    suspend operator fun invoke(caller: TodoCaller, sessionId: String?, input: TodoStopInput): TodoStopDecision
}

/**
 * [StopTodoUseCase].
 *
 * @param access Finds the session.
 * @param repository The todos.
 * @param verifier The server's own checks and the drop answers.
 * @param waits Asks the owner when the session is stuck.
 */
class StopTodoUseCaseImpl(
    private val access: TodoAccess,
    private val repository: TodosRepository,
    private val verifier: TodoVerifier,
    private val waits: TodoWaits,
) : StopTodoUseCase {
    override suspend fun invoke(caller: TodoCaller, sessionId: String?, input: TodoStopInput): TodoStopDecision {
        val writer = access.writer(caller, sessionId)
        val list = verifier.reconcile(writer.target.orgId, writer.target.sessionId)
        val passes = list?.let { passes(writer, it, input) }
            ?: TodoStopDecision(TodoStopAction.ALLOW, null, why = "no_list")
        return if (list == null || passes.action == TodoStopAction.ALLOW || input.phase == TodoStopPhase.BEGIN) {
            passes
        } else {
            judge(writer, list, input)
        }
    }

    /** The answer before anything is verified: a stop that passes, or `verify` (and the hook's visit recorded). */
    private suspend fun passes(writer: TodoWriter, list: TodoList, input: TodoStopInput): TodoStopDecision {
        val target = writer.target
        val why = when {
            // An administrator turned the hook off for the session's owner: nothing is run, counted or written.
            !repository.stopHookEnabled(target.orgId, writer.owner.memberId) -> "hook_disabled"
            list.status != TodoListStatus.ACTIVE -> "finished"
            input.background -> "background_task"
            waits.anyOpen(target.orgId, writer.owner.segments) -> "wait_open"
            else -> null
        }
        return when {
            why == "hook_disabled" || why == "finished" -> TodoStopDecision(TodoStopAction.ALLOW, list, why = why)
            why != null -> TodoStopDecision(TodoStopAction.ALLOW, visit(target), why = why)
            // The hook asked about this turn's end: one small write per stop, at its first question.
            input.phase == TodoStopPhase.BEGIN -> TodoStopDecision(TodoStopAction.VERIFY, visit(target))
            else -> TodoStopDecision(TodoStopAction.VERIFY, list)
        }
    }

    /** Records when the Stop hook last asked about the session. */
    private suspend fun visit(target: TodoTarget): TodoList {
        val now = verifier.now()
        return repository.mutate(target.orgId, target.sessionId) { current ->
            current.copy(lastHookAt = now).let { it to it }
        }
    }

    private suspend fun judge(writer: TodoWriter, list: TodoList, input: TodoStopInput): TodoStopDecision {
        validate(input)
        val target = writer.target
        val wanted = input.items.mapNotNull { list.item(it.itemId) }
        val observed = if (wanted.isEmpty()) {
            emptyMap()
        } else {
            verifier.serverChecks(target.orgId, writer.owner.segments, wanted)
        }
        val now = verifier.now()
        // The ticks, the final verification and the counter are one change under the list's lock: two replicas
        // stopping at once count once each, and only one of them sees the limit.
        val judged = repository.mutate(target.orgId, target.sessionId) { current ->
            val outcome = judged(current, input, observed, now)
            outcome.list.copy(updatedAt = now) to outcome.copy(list = outcome.list.copy(updatedAt = now))
        }
        val after = judged.list
        return when {
            judged.settled -> TodoStopDecision(TodoStopAction.ALLOW, after, why = "settled")
            // A `settle` that does not finish the list reopened an item: it is never all closed.
            input.phase == TodoStopPhase.VERIFY && after.allClosed -> TodoStopDecision(TodoStopAction.SETTLE, after)
            judged.escalate -> escalate(writer, after, input.lastMessage, now)
            else -> TodoStopDecision(TodoStopAction.BLOCK, after, reason = TodoMessages.resume(after))
        }
    }

    private data class Judged(val list: TodoList, val settled: Boolean, val escalate: Boolean)

    private fun judged(
        current: TodoList,
        input: TodoStopInput,
        observed: Map<String, Map<TodoCheck, TodoCheckResult>>,
        now: Instant,
    ): Judged {
        var changed = if (input.phase == TodoStopPhase.VERIFY) {
            tick(current, input, observed, now)
        } else {
            TodoRules.settle(current, settleResults(current, input, observed), now)
        }
        val settled = changed.status == TodoListStatus.DONE
        var escalate = false
        if (!settled && !changed.allClosed) {
            val stop = TodoRules.stop(changed)
            escalate = stop.escalate
            // At the limit the counter starts again here: the replica that sees it asks the owner.
            changed = if (stop.escalate) TodoRules.resetStall(stop.list) else stop.list
        }
        return Judged(changed, settled, escalate)
    }

    /** `verify`: the PRs no item covers become items, and each open item whose checks all passed is done. */
    private fun tick(
        current: TodoList,
        input: TodoStopInput,
        observed: Map<String, Map<TodoCheck, TodoCheckResult>>,
        now: Instant,
    ): TodoList {
        var changed = current
        input.orphans.forEach { changed = TodoRules.addOrphan(changed, it, now) }
        for (report in input.items) {
            val item = changed.item(report.itemId)
            if (item != null && (item.status == TodoItemStatus.TODO || item.status == TodoItemStatus.DOING)) {
                val results = verifier.results(item, report.checks, observed[item.id].orEmpty())
                changed = TodoRules.complete(changed, item.id, results, now).list
            }
        }
        return changed
    }

    /** `settle`: the results of each done item. */
    private fun settleResults(
        current: TodoList,
        input: TodoStopInput,
        observed: Map<String, Map<TodoCheck, TodoCheckResult>>,
    ): Map<String, List<TodoCheckResult>> = input.items.mapNotNull { report ->
        current.item(report.itemId)?.takeIf { it.status == TodoItemStatus.DONE }?.let { item ->
            item.id to verifier.results(item, report.checks, observed[item.id].orEmpty())
        }
    }.toMap()

    /**
     * The third stop without progress: ask the owner outside any lock, mark the stuck item as waiting on that
     * answer, and let the stop pass.
     */
    private suspend fun escalate(
        writer: TodoWriter,
        after: TodoList,
        lastMessage: String?,
        now: Instant,
    ): TodoStopDecision {
        val target = writer.target
        val stuck = TodoRules.next(after) ?: after.items.firstOrNull { it.status == TodoItemStatus.DOING }
        val title = TodoMessages.blockedTitle(after)
        val waitId = waits.askBlocked(writer.owner, title, TodoMessages.blockedBody(after, lastMessage), stuck != null)
        val saved = withdrawingOnFailure(waits, writer.owner, waitId) {
            repository.mutate(target.orgId, target.sessionId) { current ->
                // The item may have moved since the judgement: only a still open one waits on the answer.
                val open = stuck?.takeIf { item ->
                    current.items.any {
                        it.id == item.id && (it.status == TodoItemStatus.TODO || it.status == TodoItemStatus.DOING)
                    }
                }
                var changed = current
                if (open != null) changed = TodoRules.requestDrop(changed, open.id, STUCK_REASON, waitId)
                val text = "Blocked on ${stuck?.id ?: "the todo"}: a wait asks the owner."
                changed = TodoRules.recordEvent(changed, TodoHookEvent.BLOCKED, text, now).copy(updatedAt = now)
                changed to changed
            }
        }
        return TodoStopDecision(TodoStopAction.ALLOW, saved, why = "escalated", escalated = true)
    }

    private fun validate(input: TodoStopInput) {
        val repeated = input.items.map { it.itemId }.distinct().size != input.items.size
        if (input.items.size > TodoRules.MAX_ITEMS || input.orphans.size > MAX_ORPHANS || repeated) {
            throw invalidTodo(
                "invalid_stop",
                "Report each item once, at most ${TodoRules.MAX_ITEMS} items and $MAX_ORPHANS pull requests.",
            )
        }
    }

    private companion object {
        const val MAX_ORPHANS = 5
        const val STUCK_REASON = "The agent stopped three times without progress on this item."
    }
}
