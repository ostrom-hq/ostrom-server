// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.todos.domain

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid

/**
 * An item put in progress.
 *
 * @property list The list.
 * @property marker The marker to put in the body of the item's PR, so it is found whatever its number.
 */
data class StartedItem(val list: TodoList, val marker: String)

/**
 * Changing a session's todo: its person or their agent token only. No method deletes an item, rewrites a condition
 * or marks an item done without its checks.
 */
interface ChangeTodoUseCase {
    /**
     * Creates the session's list, once.
     *
     * @return The list.
     * @throws TodoException `todo_exists`, or a refused reference, item or check.
     */
    suspend fun create(
        caller: TodoCaller,
        sessionId: String?,
        reference: String,
        refs: List<TodoRef>,
        items: List<TodoItemDefinition>,
    ): TodoList

    /**
     * Adds a discovered item with its reason.
     *
     * @return The list.
     */
    suspend fun add(caller: TodoCaller, sessionId: String?, item: TodoItemDefinition, reason: String): TodoList

    /**
     * Puts an item in progress.
     *
     * @param background The item is given to a background agent: the item in progress stays so.
     * @param agent That agent's name.
     * @return The list and the marker of the item's PR.
     */
    suspend fun start(
        caller: TodoCaller,
        sessionId: String?,
        itemId: String,
        background: Boolean,
        agent: String?,
    ): StartedItem

    /**
     * Appends a dated note.
     *
     * @return The list.
     */
    suspend fun note(caller: TodoCaller, sessionId: String?, itemId: String, text: String): TodoList

    /**
     * Records what the checks of an item observed. The item is done only when every check of its stored condition
     * passed; `artifact_published` and `wait_answered` are verified by the server, whatever is reported.
     *
     * @return The list with the item done.
     * @throws TodoException `not_done` (409) with the proof kept, `invalid_proof`.
     */
    suspend fun complete(
        caller: TodoCaller,
        sessionId: String?,
        itemId: String,
        reports: List<TodoCheckReport>,
    ): TodoList

    /**
     * Binds a PR, a release, an artifact or a wait to an item, once.
     *
     * @return The list.
     * @throws TodoException `already_linked`, `invalid_link`.
     */
    suspend fun link(
        caller: TodoCaller,
        sessionId: String?,
        itemId: String,
        link: TodoLink,
        replaceClosed: Boolean,
    ): TodoList

    /**
     * Asks the session's owner to drop an item: a wait with `yes` and `no`; the item is `drop_requested` until the
     * answer.
     *
     * @return The list.
     */
    suspend fun requestDrop(caller: TodoCaller, sessionId: String?, itemId: String, reason: String): TodoList

    /**
     * Records a failure of the Stop hook on the list, so a broken hook shows.
     *
     * @return The list.
     */
    suspend fun hookError(caller: TodoCaller, sessionId: String?, text: String): TodoList

    /**
     * Clears the session's closed todo, as if it never had one, so a new one can be created (TeamCode #266).
     *
     * @return The list deleted, for the audit log.
     * @throws TodoException `todo_active` while the list is active, `todo_not_found` without one.
     */
    suspend fun reset(caller: TodoCaller, sessionId: String?): TodoList
}

/**
 * [ChangeTodoUseCase].
 *
 * @param access Finds the session.
 * @param repository The todos.
 * @param verifier The server's own checks and the drop answers.
 * @param waits Asks the owner.
 */
class ChangeTodoUseCaseImpl(
    private val access: TodoAccess,
    private val repository: TodosRepository,
    private val verifier: TodoVerifier,
    private val waits: TodoWaits,
) : ChangeTodoUseCase {
    override suspend fun create(
        caller: TodoCaller,
        sessionId: String?,
        reference: String,
        refs: List<TodoRef>,
        items: List<TodoItemDefinition>,
    ): TodoList {
        val target = access.writer(caller, sessionId).target
        val now = verifier.now()
        val list = TodoRules.create(Uuid.random(), target.orgId, target.sessionId, reference, refs, items, now)
        // The session has one already: todo_exists.
        if (!repository.create(list)) TodoRules.requireNoList(list)
        return list
    }

    override suspend fun add(
        caller: TodoCaller,
        sessionId: String?,
        item: TodoItemDefinition,
        reason: String,
    ): TodoList =
        change(caller, sessionId) { list, now -> TodoRules.add(list, item, reason).copy(updatedAt = now) }

    override suspend fun start(
        caller: TodoCaller,
        sessionId: String?,
        itemId: String,
        background: Boolean,
        agent: String?,
    ): StartedItem {
        val list = change(caller, sessionId) { list, now ->
            TodoRules.start(list, itemId, background, agent?.take(TodoRules.MAX_NAME), now).copy(updatedAt = now)
        }
        return StartedItem(list, TodoRules.marker(list.id, itemId))
    }

    override suspend fun note(caller: TodoCaller, sessionId: String?, itemId: String, text: String): TodoList =
        change(caller, sessionId) { list, now -> TodoRules.note(list, itemId, text, now).copy(updatedAt = now) }

    override suspend fun complete(
        caller: TodoCaller,
        sessionId: String?,
        itemId: String,
        reports: List<TodoCheckReport>,
    ): TodoList {
        val writer = access.writer(caller, sessionId)
        val target = writer.target
        // What the server sees itself it reads before the lock: no network call is held under it.
        val current = repository.find(target.orgId, target.sessionId)?.item(itemId)
        val observed = current?.let {
            verifier.serverChecks(target.orgId, writer.owner.segments, listOf(it))[it.id]
        }.orEmpty()
        val now = verifier.now()
        val outcome = repository.mutate(target.orgId, target.sessionId) { list ->
            val item = list.item(itemId) ?: throw unknownItem(itemId)
            val done = TodoRules.complete(list, itemId, verifier.results(item, reports, observed), now)
            done.list.copy(updatedAt = now) to done
        }
        if (outcome is TodoCompletion.Refused) throw outcome.failure
        return outcome.list.copy(updatedAt = now)
    }

    override suspend fun link(
        caller: TodoCaller,
        sessionId: String?,
        itemId: String,
        link: TodoLink,
        replaceClosed: Boolean,
    ): TodoList = change(caller, sessionId) { list, now ->
        TodoRules.link(list, itemId, link, replaceClosed, now).copy(updatedAt = now)
    }

    override suspend fun requestDrop(caller: TodoCaller, sessionId: String?, itemId: String, reason: String): TodoList {
        val writer = access.writer(caller, sessionId)
        val target = writer.target
        val current = verifier.reconcile(target.orgId, target.sessionId) ?: throw todoNotFound()
        val item = current.item(itemId) ?: throw unknownItem(itemId)
        // Refuses before a wait is opened.
        TodoRules.requestDrop(current, itemId, reason, null)
        val waitId = waits.askDrop(writer.owner, item, reason)
        val now = verifier.now()
        return withdrawingOnFailure(waits, writer.owner, waitId) {
            repository.mutate(target.orgId, target.sessionId) { list ->
                TodoRules.requestDrop(list, itemId, reason, waitId).copy(updatedAt = now).let { it to it }
            }
        }
    }

    override suspend fun hookError(caller: TodoCaller, sessionId: String?, text: String): TodoList =
        change(caller, sessionId) { list, now ->
            TodoRules.recordEvent(list, TodoHookEvent.HOOK_ERROR, text, now).copy(updatedAt = now)
        }

    override suspend fun reset(caller: TodoCaller, sessionId: String?): TodoList {
        val target = access.writer(caller, sessionId).target
        return repository.reset(target.orgId, target.sessionId, TodoRules::requireClosed)
    }

    private suspend fun change(
        caller: TodoCaller,
        sessionId: String?,
        rule: (TodoList, kotlin.time.Instant) -> TodoList,
    ): TodoList {
        val target = access.writer(caller, sessionId).target
        val now = verifier.now()
        return repository.mutate(target.orgId, target.sessionId) { list -> rule(list, now).let { it to it } }
    }
}

/**
 * Runs [block], and withdraws the wait [waitId] when it fails or is cancelled: a wait whose purpose was not stored
 * would ask the owner for nothing.
 *
 * @return What [block] returns.
 */
internal suspend fun <T> withdrawingOnFailure(
    waits: TodoWaits,
    owner: TodoOwner,
    waitId: String,
    block: suspend () -> T,
): T {
    // No `finally` with a flag: the compiler copies a finally per exit, and the copy of the normal exit has a branch
    // no test can take (BudgetGuard).
    try {
        return block()
    } catch (e: CancellationException) {
        withContext(NonCancellable) { cancelQuietly(waits, owner, waitId) }
        throw e
    } catch (e: Exception) {
        withContext(NonCancellable) { cancelQuietly(waits, owner, waitId) }
        throw e
    }
}

private suspend fun cancelQuietly(waits: TodoWaits, owner: TodoOwner, waitId: String) {
    try {
        waits.cancel(owner, waitId)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // The wait stays open: its owner answers or cancels it, and the item is unchanged either way.
    }
}
